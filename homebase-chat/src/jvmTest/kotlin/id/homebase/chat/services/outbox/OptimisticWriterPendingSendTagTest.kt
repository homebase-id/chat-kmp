package id.homebase.chat.services.outbox

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.chat.services.ChatMessageSenderServiceTestFixture
import id.homebase.chat.services.ChatProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

/**
 * Regression test for the SQLiteConstraintException seen in field logs when a
 * user tried to edit a chat message whose previous send had not yet confirmed.
 *
 * Scenario reproduced:
 *   1. Send a new message (offline outbox: it never confirms). The optimistic
 *      writer stamps `localAppData.tags = [isPendingSendTag]` on the new row.
 *   2. Without waiting for confirmation, edit the same message. Pre-fix,
 *      [OptimisticWriter.writeUpdate] appended `isPendingSendTag` again,
 *      producing a duplicate in the input list. `performBaseUpsert`'s
 *      `deleteByFile` then `insertLocalTag` loop blew up on the duplicate
 *      because `DriveLocalTagIndex` has `UNIQUE(identityId,driveId,fileId,tagId)`
 *      and `insertLocalTag` is a plain INSERT — the whole optimistic write
 *      transaction rolled back, leaving the row stuck for every subsequent
 *      edit attempt.
 */
class OptimisticWriterPendingSendTagTest {

    @Test
    fun editingPendingMessage_doesNotDuplicatePendingSendTag() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(scope = this)
            val conversationId = fixture.seedConversation(others = listOf("alice.test"))

            val messageId = Uuid.random()
            service.sendNewMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "first version",
                previousMessageUniqueId = null,
                payloadBundle = null,
            )

            // Sanity: send path stamped exactly one isPendingSendTag.
            val seedRow = fixture.dbm.driveMainIndex.selectHomebaseFileByUnique(
                identityId = fixture.testIdentityId,
                driveId = fixture.chatDriveId,
                uniqueId = messageId,
            ) ?: error("expected optimistic row for $messageId after sendNewMessage")
            val tagsAfterSend = fixture.dbm.driveLocalTagIndex.selectByFile(
                identityId = fixture.testIdentityId,
                driveId = fixture.chatDriveId,
                fileId = seedRow.fileId,
            ).map { it.tagId }
            assertEquals(
                listOf(ChatProtocol.isPendingSendTag),
                tagsAfterSend,
                "send path should stamp exactly one isPendingSendTag",
            )

            // Edit the still-pending message. The interesting bit: the row already
            // carries isPendingSendTag, and writeUpdate must not re-add it.
            val newKeyHeader = KeyHeader(
                iv = ByteArrayUtil.getRndByteArray(16),
                aesKey = seedRow.keyHeader.aesKey,
            )
            val editMetadata = UploadFileMetadata(
                allowDistribution = true,
                isEncrypted = true,
                appData = UploadAppFileMetaData(
                    uniqueId = messageId,
                    groupId = conversationId,
                    fileType = ChatProtocol.MessageFileType,
                    dataType = 0,
                    userDate = 0L,
                    content = "edited version",
                ),
            )

            // Pre-fix: this throws SQLiteConstraintException.
            fixture.optimisticWriter.writeUpdate(
                driveId = fixture.chatDriveId,
                keyHeader = newKeyHeader,
                unecryptedMetadata = editMetadata,
            )

            val editedRow = fixture.dbm.driveMainIndex.selectHomebaseFileByUnique(
                identityId = fixture.testIdentityId,
                driveId = fixture.chatDriveId,
                uniqueId = messageId,
            ) ?: error("expected row for $messageId after writeUpdate")
            val tagsAfterEdit = fixture.dbm.driveLocalTagIndex.selectByFile(
                identityId = fixture.testIdentityId,
                driveId = fixture.chatDriveId,
                fileId = editedRow.fileId,
            ).map { it.tagId }
            assertEquals(
                listOf(ChatProtocol.isPendingSendTag),
                tagsAfterEdit,
                "edit must leave exactly one isPendingSendTag, not duplicate it",
            )
        }
    }
}
