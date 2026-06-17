package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.common.BatchResult
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.Outbox
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.poll.PollDescriptor
import id.homebase.chat.services.content.MessageContent
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Verifies that [ChatMessageSenderService.updateMessage] preserves the
 * original message's typed [dataType] instead of hardcoding 0.
 *
 * Task 3 — TDD fix: the `UploadAppFileMetaData(dataType = ...)` site in the
 * normal (already-delivered) path inside `updateMessage` must derive the type
 * from the existing message's `messageContent`, not use a literal 0.
 *
 * Pending-create path: the same one-line fix is applied but is not separately
 * exercised here because it would require scripting a live outbox pending-create
 * row whose JSON matches what `updateMessage` reads via
 * `outboxSync.pendingUploadFileRequest` — significantly more fixture machinery
 * for an identical expression at an adjacent line.
 */
class ChatMessageSenderServiceUpdateMessageTest {

    /**
     * A [MessageLookup] backed purely by an in-memory list so tests can seed
     * arbitrary [MessageUiModel]s — including ones with [MessageContent.Poll].
     * The factory lambda signature matches the [ChatMessageSenderServiceTestFixture.build]
     * `messageLookup` parameter.
     */
    private class SeedableMessageLookup : MessageLookup {
        val records = mutableListOf<MessageUiModel>()

        fun seed(model: MessageUiModel) { records += model }

        override suspend fun getMessage(messageId: Uuid): MessageUiModel? =
            records.firstOrNull { it.id == messageId }

        override suspend fun getMessageFile(messageId: Uuid) =
            error("SeedableMessageLookup.getMessageFile not used in updateMessage tests")

        override suspend fun getMessages(
            messageIds: List<Uuid>,
            conversationId: Uuid,
        ): BatchResult<MessageUiModel> {
            val wanted = messageIds.toSet()
            return BatchResult(
                records = records.filter { it.id in wanted && it.conversationId == conversationId },
                hasMoreRows = false,
                cursor = QueryBatchCursor(),
            )
        }

        override suspend fun loadFullMessage(conversationId: Uuid, messageId: Uuid): String? = null

        override fun findCachedFileId(messageId: Uuid): Uuid? = null
    }

    /** Deserialize an outbox row as [UpdateFileByUniqueIdRequest], or null if it's a different type. */
    private fun Outbox.asUpdateFileRequestOrNull(): UpdateFileByUniqueIdRequest? {
        if (this.uploadType != DriveOutboxUploader.UpdateFile) return null
        return try {
            OdinSystemSerializer.deserialize<UpdateFileByUniqueIdRequest>(this.json.decodeToString())
        } catch (_: Throwable) {
            null
        }
    }

    // -----------------------------------------------------------------------
    // Normal (already-delivered) path
    // -----------------------------------------------------------------------

    /**
     * RED before the fix: enqueued [UpdateFileByUniqueIdRequest] carries
     * `appData.dataType == 0` (hardcoded) instead of 214.
     *
     * GREEN after the fix: `appData.dataType == ChatProtocol.ChatPollMessageDataType`.
     *
     * Setup:
     * - Pre-seed a DriveMainIndex row via [OptimisticWriter.writeNewFile] so that
     *   [OptimisticWriter.writeUpdate] (called inside `updateMessage`) can find it.
     *   `writeNewFile` is DB-only (no outbox enqueue), so the outbox stays empty
     *   and `updateMessage` takes the normal already-delivered path.
     * - A [SeedableMessageLookup] record carries `messageContent = Poll(descriptor)`
     *   so the service can derive `dataType = 214`.
     */
    @Test
    fun `updateMessage on a Poll message preserves dataType 214 in the enqueued update request`() = runTest {
        val messageLookup = SeedableMessageLookup()

        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(
                messageLookup = { _: DatabaseManager -> messageLookup },
            )

            val conversationId = fixture.seedConversation(others = listOf("bob.test"))
            val messageId = Uuid.random()
            val versionTag = Uuid.random()
            val keyHeader = KeyHeader.newRandom16()

            val descriptor = PollDescriptor(
                question = "Best day?",
                options = listOf("Monday", "Tuesday"),
            )

            // 1. Pre-seed the DriveMainIndex row (no outbox row added — writeNewFile
            //    is DB-only). This satisfies OptimisticWriter.writeUpdate's
            //    "no file by uid" guard inside updateMessage.
            fixture.optimisticWriter.writeNewFile(
                driveId = fixture.chatDriveId,
                keyHeader = keyHeader,
                unecryptedMetadata = UploadFileMetadata(
                    allowDistribution = true,
                    isEncrypted = true,
                    appData = UploadAppFileMetaData(
                        uniqueId = messageId,
                        groupId = conversationId,
                        fileType = ChatProtocol.MessageFileType,
                        dataType = ChatProtocol.ChatPollMessageDataType,
                        userDate = 1_000L,
                        content = OdinSystemSerializer.serialize(descriptor),
                    ),
                ),
                originalRecipientCount = 1,
                fileSystemType = FileSystemType.Standard,
            )

            // 2. Seed the MessageUiModel with messageContent = Poll so updateMessage
            //    can derive dataType 214. versionTag must match what updateMessage
            //    checks against msg.versionTag.
            messageLookup.seed(
                MessageUiModel(
                    id = messageId,
                    globalTransitId = null,
                    fileId = Uuid.random(),
                    conversationId = conversationId,
                    content = descriptor.summaryLine(),
                    userDate = Instant.fromEpochMilliseconds(1_000L),
                    modified = null,
                    created = Instant.fromEpochMilliseconds(1_000L),
                    originalAuthor = OdinId(fixture.testDomain),
                    sender = OdinId(fixture.testDomain),
                    displayName = fixture.testDomain,
                    isDeleted = false,
                    isPendingSend = false,
                    versionTag = versionTag,
                    messageAppData = MessageAppData(),
                    reactionPreview = null,
                    previewThumbnail = null,
                    payloads = persistentListOf(),
                    keyHeader = keyHeader,
                    hasMore = false,
                    // The critical field: non-null Poll so updateMessage derives 214.
                    messageContent = MessageContent.Poll(descriptor),
                )
            )

            // 3. Call updateMessage — normal already-delivered path (outbox is empty
            //    so pendingUploadFileRequest returns null).
            service.updateMessage(
                messageId = messageId,
                versionTag = versionTag,
                content = OdinSystemSerializer.serialize(descriptor.copy(closed = true)),
            )

            // 4. The UpdateFileByUniqueIdRequest must be in the outbox.
            val rows = fixture.drainOutboxInDependencyOrder()
            val updateRow = rows.mapNotNull { it.asUpdateFileRequestOrNull() }.firstOrNull()
            assertNotNull(updateRow, "an UpdateFileByUniqueIdRequest must be enqueued after updateMessage")

            // 5. dataType must be preserved as 214, not reset to 0.
            assertEquals(
                ChatProtocol.ChatPollMessageDataType,
                updateRow.metadata.appData.dataType,
                "updateMessage must preserve the Poll's dataType (214) — not hardcode 0",
            )
        }
    }
}
