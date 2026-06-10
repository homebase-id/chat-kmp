package id.homebase.chat.services.outbox

import id.homebase.chat.services.ChatMessageSenderServiceTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Deleting a message that is still pending in the outbox must also cancel its
 * queued send — otherwise the "deleted" message still uploads to the server
 * once the outbox drains. [OptimisticWriter.removeOptimisticFile] is the shared
 * primitive (used by message delete and leave-group rollback); it must drop the
 * local file AND its outbox row together.
 */
class RemoveOptimisticFileCancelsSendTest {

    @Test
    fun removeOptimisticFile_cancelsQueuedSend() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val conversationId = fixture.seedConversation(others = listOf("alice.test"))
            val messageId = Uuid.random()

            service.sendNewMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "to be deleted before it sends",
                previousMessageUniqueId = null,
                payloadBundle = null,
            )

            // The optimistic file + its queued send both exist.
            assertNotNull(
                fixture.dbm.driveMainIndex.selectHomebaseFileByUnique(
                    fixture.testIdentityId, fixture.chatDriveId, messageId,
                ),
                "expected an optimistic file after sendNewMessage",
            )
            val outboxBefore = fixture.dbm.outbox.count()
            assertTrue(outboxBefore >= 1, "expected a queued send row after sendNewMessage")

            // Delete-before-send.
            fixture.optimisticWriter.removeOptimisticFile(fixture.chatDriveId, messageId)

            assertNull(
                fixture.dbm.driveMainIndex.selectHomebaseFileByUnique(
                    fixture.testIdentityId, fixture.chatDriveId, messageId,
                ),
                "local file should be removed",
            )
            assertEquals(
                outboxBefore - 1,
                fixture.dbm.outbox.count(),
                "removeOptimisticFile must cancel exactly the message's queued send",
            )
        }
    }

    @Test
    fun removeOptimisticFile_noOpWhenAlreadySent() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val conversationId = fixture.seedConversation(others = listOf("alice.test"))
            val messageId = Uuid.random()

            service.sendNewMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "already confirmed",
                previousMessageUniqueId = null,
                payloadBundle = null,
            )

            // Simulate the send confirming: the pending tag is cleared on sync-back.
            fixture.optimisticWriter.updateLocalTags(fixture.chatDriveId, messageId, emptyList())
            val outboxBefore = fixture.dbm.outbox.count()

            fixture.optimisticWriter.removeOptimisticFile(fixture.chatDriveId, messageId)

            // Not pending → must NOT delete the file or touch the outbox.
            assertNotNull(
                fixture.dbm.driveMainIndex.selectHomebaseFileByUnique(
                    fixture.testIdentityId, fixture.chatDriveId, messageId,
                ),
                "a confirmed (non-pending) message must not be removed",
            )
            assertEquals(
                outboxBefore,
                fixture.dbm.outbox.count(),
                "a no-op removal must not touch the outbox",
            )
        }
    }
}
