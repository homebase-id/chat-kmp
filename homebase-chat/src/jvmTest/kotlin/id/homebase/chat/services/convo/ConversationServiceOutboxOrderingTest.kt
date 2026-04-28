package id.homebase.chat.services.convo

import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.StatusMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Static-shape tests for the outbox dependency chain that [ConversationService]
 * produces. Each test asserts the `(uniqueId, dependencyUniqueId)` tuples of the
 * outbox rows that result from a single service call, plus the
 * `previousMessageUniqueId` values passed to [FakeStatusMessageSender] for any
 * status messages emitted along the way.
 *
 * Why these tests exist: recent refactors serialized three flows
 * ([ConversationService.createConversation], [ConversationService.updateAdmins],
 * [ConversationService.updateGroupMembers]) so each step in a group mutation
 * waits for the previous one in the local outbox. Without coverage, a future
 * edit could quietly turn the chain back into a fan-out (e.g. by removing a
 * `dependencyUniqueId = …` argument or dropping a `previousMessageUniqueId`
 * thread-through), and recipients could see the admin file or a status message
 * land before the conversation file — the failure mode that produced the
 * orphan-recovery placeholder bug.
 *
 * Out of scope: whether [id.homebase.api.sync.database.OutboxSync] actually
 * releases items in dependency order at runtime (that's its own contract,
 * tested in homebase-api), and whether the server-side Transit protocol
 * preserves the chain across identities (integration concern).
 */
class ConversationServiceOutboxOrderingTest {

    @Test
    fun createConversation_group_chainsConversationThenAdminThenStatus() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val bob = "bob.test"

            val result = service.createConversation(
                recipients = listOf(OdinId(alice), OdinId(bob)),
                title = "the fellowship",
                payloadBundle = null,
            )

            val conversationId = result.conversationId
            val adminUniqueId = ChatProtocol.getAdminFileUniqueId(conversationId)

            val rows = fixture.drainOutboxInDependencyOrder()
            val convoRow = rows.single { it.uniqueId == conversationId }
            val adminRow = rows.single { it.uniqueId == adminUniqueId }

            assertNull(
                convoRow.dependencyUniqueId,
                "conversation file is the chain head — must not depend on anything"
            )
            assertEquals(
                conversationId,
                adminRow.dependencyUniqueId,
                "admin file outbox row must depend on the conversation file"
            )

            val started = fixture.statusMessageSender.calls.single {
                it.statusMessage.statusMessage == StatusMessage.GroupConversationStarted
            }
            assertEquals(
                adminUniqueId,
                started.previousMessageUniqueId,
                "GroupConversationStarted must chain off the admin file, not the conversation file"
            )
        }
    }

    @Test
    fun createConversation_oneToOne_doesNotEnqueueAdminFile_andNoStatusMessage() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"

            val result = service.createConversation(
                recipients = listOf(OdinId(alice)),
                title = null,
                payloadBundle = null,
            )

            val adminUniqueId = ChatProtocol.getAdminFileUniqueId(result.conversationId)
            val rows = fixture.drainOutboxInDependencyOrder()

            assertTrue(
                rows.none { it.uniqueId == adminUniqueId },
                "1:1 must not enqueue an admin file"
            )
            assertTrue(
                fixture.statusMessageSender.calls.isEmpty(),
                "1:1 must not emit a GroupConversationStarted status message"
            )
        }
    }

    @Test
    fun updateAdmins_chainsStatusMessagesAfterAdminFile() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val bob = "bob.test"
            // testDomain + alice are admins so removing alice still leaves one admin (testDomain)
            // and adding bob is valid (bob is a participant).
            val groupId = fixture.seedGroup(
                others = listOf(alice, bob),
                adminDomains = listOf(fixture.testDomain, alice),
            )
            val adminUniqueId = ChatProtocol.getAdminFileUniqueId(groupId)

            service.updateAdmins(
                conversationId = groupId,
                add = listOf(OdinId(bob)),
                remove = listOf(OdinId(alice)),
            )

            val rows = fixture.drainOutboxInDependencyOrder()
            val adminRow = rows.single { it.uniqueId == adminUniqueId }
            assertEquals(
                groupId,
                adminRow.dependencyUniqueId,
                "admin file update must chain off the conversation file"
            )

            val calls = fixture.statusMessageSender.calls
            assertEquals(2, calls.size, "expect one ConversationAdminAdded + one ConversationAdminRemoved")
            assertEquals(
                adminUniqueId,
                calls[0].previousMessageUniqueId,
                "first admin-status message must chain off the admin file"
            )
            assertEquals(
                calls[0].messageUniqueId,
                calls[1].previousMessageUniqueId,
                "second admin-status message must chain off the first one"
            )
        }
    }

    @Test
    fun updateGroupMembers_serializesRemovedThenConversationUpdateThenAdded() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"   // will be removed
            val bob = "bob.test"       // will be removed
            val carol = "carol.test"   // stays
            val dave = "dave.test"     // will be added
            val groupId = fixture.seedGroup(
                others = listOf(alice, bob, carol),
                adminDomains = listOf(fixture.testDomain),
            )

            service.updateGroupMembers(
                conversationId = groupId,
                remove = listOf(OdinId(alice), OdinId(bob)),
                add = listOf(OdinId(dave)),
            )

            val rows = fixture.drainOutboxInDependencyOrder()
            val convoUpdateRow = rows.single { it.uniqueId == groupId }
            val calls = fixture.statusMessageSender.calls
            assertEquals(3, calls.size, "expect 2 ConversationMemberRemoved + 1 ConversationMemberAdded")

            // Removed messages chain head → tail
            assertNull(
                calls[0].previousMessageUniqueId,
                "first removed-status starts the chain (no upstream dep — pre-existing behavior)"
            )
            assertEquals(
                calls[0].messageUniqueId,
                calls[1].previousMessageUniqueId,
                "second removed-status chains off the first"
            )

            // Conversation update waits for the trailing removed-status
            assertEquals(
                calls[1].messageUniqueId,
                convoUpdateRow.dependencyUniqueId,
                "conversation update must wait for the last removed-status message"
            )

            // Added messages chain off the conversation update
            assertEquals(
                groupId,
                calls[2].previousMessageUniqueId,
                "first added-status must chain off the conversation file update"
            )
        }
    }

    @Test
    fun updateGroupMembers_noRemovals_conversationUpdateHasNullDependency() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val bob = "bob.test"
            val groupId = fixture.seedGroup(
                others = listOf(alice),
                adminDomains = listOf(fixture.testDomain),
            )

            service.updateGroupMembers(
                conversationId = groupId,
                remove = emptyList(),
                add = listOf(OdinId(bob)),
            )

            val rows = fixture.drainOutboxInDependencyOrder()
            val convoUpdateRow = rows.single { it.uniqueId == groupId }
            assertNull(
                convoUpdateRow.dependencyUniqueId,
                "with no removals, the conversation update has no upstream dep " +
                        "(regression guard for the new dependencyUniqueId = previousMessageId line)"
            )

            val calls = fixture.statusMessageSender.calls
            assertEquals(1, calls.size, "only the added-status message")
            assertEquals(
                groupId,
                calls[0].previousMessageUniqueId,
                "added-status must chain off the conversation file update"
            )
        }
    }

    @Test
    fun updateGroupMembers_onlyRemovals_chainTerminatesAtConversationUpdate() = runTest {
        ConversationServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val alice = "alice.test"
            val bob = "bob.test"
            val groupId = fixture.seedGroup(
                others = listOf(alice, bob),
                adminDomains = listOf(fixture.testDomain),
            )

            service.updateGroupMembers(
                conversationId = groupId,
                remove = listOf(OdinId(bob)),
                add = emptyList(),
            )

            val calls = fixture.statusMessageSender.calls
            assertEquals(1, calls.size, "one removed-status, no added-status")
            assertNull(
                calls[0].previousMessageUniqueId,
                "first removed-status starts the chain"
            )

            val rows = fixture.drainOutboxInDependencyOrder()
            val convoUpdateRow = rows.single { it.uniqueId == groupId }
            assertEquals(
                calls[0].messageUniqueId,
                convoUpdateRow.dependencyUniqueId,
                "conversation update must wait for the trailing removed-status message"
            )
        }
    }
}
