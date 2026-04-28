package id.homebase.chat.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Static-shape tests for [ChatMessageSenderService.sendMessageInternal]'s
 * outbox dependency wiring. The service maintains an in-memory map of the
 * last-enqueued message per conversation so that pile-ups (offline use) drain
 * in send order rather than fanning out in parallel as soon as the
 * conversation file is acknowledged.
 *
 * The chain semantics asserted here:
 *   - Caller-supplied `previousMessageUniqueId` is honored verbatim.
 *   - When the caller passes null AND the service has previously enqueued a
 *     message in this conversation, the new message chains on that prior id.
 *   - When the caller passes null AND there is no prior message in this
 *     conversation, the new message chains on the conversation file's
 *     uniqueId — which closes the orphan-recovery race on the recipient side
 *     for brand-new conversations.
 *   - Tracking is per-conversation: a new conversation starts a fresh chain.
 */
class ChatMessageSenderServiceOutboxOrderingTest {

    @Test
    fun firstMessageInBrandNewConversation_chainsOnConversationId() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val conversationId = fixture.seedConversation(others = listOf("alice.test"))
            // Simulate "createConversation just enqueued the conversation file
            // and it's still pending" by inserting a row at uniqueId=conversationId.
            fixture.enqueuePendingConversationFileRow(conversationId)

            val messageId = Uuid.random()
            service.sendNewMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "hi",
                previousMessageUniqueId = null,
                payloadBundle = null,
            )

            val rows = fixture.drainOutboxInDependencyOrder()
            val messageRow = rows.single { it.uniqueId == messageId }
            assertEquals(
                conversationId,
                messageRow.dependencyUniqueId,
                "first message in a brand-new conversation must chain on the conversation file"
            )
        }
    }

    @Test
    fun secondAndThirdMessages_chainOnPreviousMessage_evenAcrossOfflinePileUp() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val conversationId = fixture.seedConversation(others = listOf("alice.test"))
            fixture.enqueuePendingConversationFileRow(conversationId)

            // User offline. Three messages typed in a row.
            val m1 = Uuid.random()
            val m2 = Uuid.random()
            val m3 = Uuid.random()
            service.sendNewMessage(m1, conversationId, "one", previousMessageUniqueId = null, payloadBundle = null)
            service.sendNewMessage(m2, conversationId, "two", previousMessageUniqueId = null, payloadBundle = null)
            service.sendNewMessage(m3, conversationId, "three", previousMessageUniqueId = null, payloadBundle = null)

            val rows = fixture.drainOutboxInDependencyOrder()
            val r1 = rows.single { it.uniqueId == m1 }
            val r2 = rows.single { it.uniqueId == m2 }
            val r3 = rows.single { it.uniqueId == m3 }

            assertEquals(
                conversationId, r1.dependencyUniqueId,
                "M1 chains on the conversation file (no prior message in this conversation yet)"
            )
            assertEquals(
                m1, r2.dependencyUniqueId,
                "M2 chains on M1 — preserves send order through the local outbox during offline pile-up"
            )
            assertEquals(
                m2, r3.dependencyUniqueId,
                "M3 chains on M2 — same"
            )
        }
    }

    @Test
    fun explicitPreviousMessageUniqueId_overridesTheFallback() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val conversationId = fixture.seedConversation(others = listOf("alice.test"))

            val explicitPrev = Uuid.random()
            val messageId = Uuid.random()
            service.sendNewMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "hi",
                previousMessageUniqueId = explicitPrev,
                payloadBundle = null,
            )

            val rows = fixture.drainOutboxInDependencyOrder()
            val messageRow = rows.single { it.uniqueId == messageId }
            assertEquals(
                explicitPrev,
                messageRow.dependencyUniqueId,
                "explicit caller-supplied previousMessageUniqueId must win over the fallback"
            )
        }
    }

    @Test
    fun chainsAreTrackedPerConversation_notCrossContaminated() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val conversationA = fixture.seedConversation(others = listOf("alice.test"))
            val conversationB = fixture.seedConversation(others = listOf("bob.test"))

            // First message in A.
            val a1 = Uuid.random()
            service.sendNewMessage(a1, conversationA, "hi A", previousMessageUniqueId = null, payloadBundle = null)
            // First message in B — must NOT chain on a1 (different conversation).
            val b1 = Uuid.random()
            service.sendNewMessage(b1, conversationB, "hi B", previousMessageUniqueId = null, payloadBundle = null)
            // Second message in A — chains on a1.
            val a2 = Uuid.random()
            service.sendNewMessage(a2, conversationA, "hi A2", previousMessageUniqueId = null, payloadBundle = null)
            // Second message in B — chains on b1.
            val b2 = Uuid.random()
            service.sendNewMessage(b2, conversationB, "hi B2", previousMessageUniqueId = null, payloadBundle = null)

            val rows = fixture.drainOutboxInDependencyOrder()
            val rA1 = rows.single { it.uniqueId == a1 }
            val rB1 = rows.single { it.uniqueId == b1 }
            val rA2 = rows.single { it.uniqueId == a2 }
            val rB2 = rows.single { it.uniqueId == b2 }

            assertEquals(conversationA, rA1.dependencyUniqueId, "A1 chains on conversation A")
            assertEquals(conversationB, rB1.dependencyUniqueId, "B1 chains on conversation B")
            assertEquals(a1, rA2.dependencyUniqueId, "A2 chains on A1 (same conversation), not on B1")
            assertEquals(b1, rB2.dependencyUniqueId, "B2 chains on B1 (same conversation), not on A2")
        }
    }
}
