package id.homebase.chat.services

import id.homebase.api.client.drives.files.DriveOutboxUploader
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Deleting a chat message must respect what is queued for it:
 *  - a pending **create** (UploadNewFile, never on the server) → cancel the send,
 *    drop it locally, and enqueue NO server delete.
 *  - a pending **edit** (UpdateFile, the message IS on the server) → cancel the
 *    edit, but STILL delete the message on the server.
 *
 * Regression guard for the trap where `isPendingSend` alone can't tell these
 * apart: an edit re-stamps `isPendingSendTag`, so a sent-then-edited message
 * looks "pending" yet must still be deleted server-side.
 */
class DeleteMessageCancelsPendingTest {

    @Test
    fun delete_pendingCreate_cancelsSend_andEnqueuesNoServerDelete() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val messageId = fixture.seedDeletableMessage(
                conversationId = Uuid.random(),
                isPendingSend = true,
            )
            // A queued create — the message never reached the server.
            fixture.dbm.outbox.insert(
                driveId = fixture.chatDriveId,
                uniqueId = messageId,
                dependencyUniqueId = null,
                priority = 1L,
                uploadType = DriveOutboxUploader.UploadNewFile,
                json = "{}".encodeToByteArray(),
                filePaths = null,
            )

            service.deleteMessage(messageId, deleteForEveryone = true)

            val rows = fixture.drainOutbox()
            assertTrue(
                rows.none { it.uploadType == DriveOutboxUploader.DeleteFile },
                "a never-sent message must NOT enqueue a server delete",
            )
            assertTrue(
                rows.none { it.uniqueId == messageId },
                "the queued create must be cancelled",
            )
            assertNull(
                fixture.dbm.driveMainIndex.selectHomebaseFileByUnique(
                    fixture.testIdentityId, fixture.chatDriveId, messageId,
                ),
                "the optimistic local file must be removed",
            )
        }
    }

    /**
     * A create whose upload is IN FLIGHT (row checked out) can't be cancelled —
     * the worker already read the row, so deleting it wouldn't stop the upload;
     * it would only remove the local file and let the next sync resurrect the
     * message as a ghost. deleteMessage must refuse: keep the local file, keep
     * the row, and enqueue no server delete (the server-assigned fileId isn't
     * known yet). A second delete after the send confirms takes the normal path.
     */
    @Test
    fun delete_inFlightCreate_isRefused_nothingChanges() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val messageId = fixture.seedDeletableMessage(
                conversationId = Uuid.random(),
                isPendingSend = true,
            )
            fixture.dbm.outbox.insert(
                driveId = fixture.chatDriveId,
                uniqueId = messageId,
                dependencyUniqueId = null,
                priority = 1L,
                uploadType = DriveOutboxUploader.UploadNewFile,
                json = "{}".encodeToByteArray(),
                filePaths = null,
            )
            // Simulate the worker holding the row mid-upload.
            checkNotNull(fixture.dbm.outbox.checkout())

            service.deleteMessage(messageId, deleteForEveryone = true)

            val row = fixture.dbm.outbox.selectByDriveAndUnique(fixture.chatDriveId, messageId)
            assertNotNull(row, "the in-flight create row must survive")
            assertNotNull(row.checkOutStamp, "the row must remain checked out")
            assertNotNull(
                fixture.dbm.driveMainIndex.selectHomebaseFileByUnique(
                    fixture.testIdentityId, fixture.chatDriveId, messageId,
                ),
                "the local file must be kept — removing it would create a ghost",
            )
            assertTrue(
                fixture.drainOutbox().none { it.uploadType == DriveOutboxUploader.DeleteFile },
                "no server delete may be enqueued for a file whose fileId is not yet server-confirmed",
            )
        }
    }

    @Test
    fun delete_pendingEdit_cancelsEdit_butStillDeletesOnServer() = runTest {
        ChatMessageActionServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val convoId = fixture.seedOneOnOneConversation(other = "alice.test")
            val messageId = fixture.seedDeletableMessage(
                conversationId = convoId,
                // an edit re-stamped isPendingSendTag, so this "looks" pending…
                isPendingSend = true,
            )
            // …but the message IS on the server: what's queued is an EDIT, not a create.
            fixture.dbm.outbox.insert(
                driveId = fixture.chatDriveId,
                uniqueId = messageId,
                dependencyUniqueId = null,
                priority = 1L,
                uploadType = DriveOutboxUploader.UpdateFile,
                json = "{}".encodeToByteArray(),
                filePaths = null,
            )

            service.deleteMessage(messageId, deleteForEveryone = true)

            val rows = fixture.drainOutbox()
            assertTrue(
                rows.none { it.uploadType == DriveOutboxUploader.UpdateFile },
                "the queued edit must be cancelled",
            )
            assertTrue(
                rows.any { it.uploadType == DriveOutboxUploader.DeleteFile },
                "a message already on the server must STILL be deleted there",
            )
        }
    }
}
