package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.common.BatchResult
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
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
import kotlin.test.assertTrue
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

        /**
         * `updateMessage` reads the original (un-clamped) `appData.userDate` off the
         * header file, so tests back this with the fixture's real DriveMainIndex
         * (see [ChatMessageSenderServiceUpdateMessageTest.dbFileLookup]).
         */
        var fileLookup: suspend (Uuid) -> HomebaseFile? = { null }

        fun seed(model: MessageUiModel) { records += model }

        override suspend fun getMessage(messageId: Uuid): MessageUiModel? =
            records.firstOrNull { it.id == messageId }

        override suspend fun getMessageFile(messageId: Uuid): HomebaseFile? =
            fileLookup(messageId)

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

    /** Deserialize an outbox row as a create ([UploadFileRequest]), or null if it's a different type. */
    private fun Outbox.asUploadFileRequestOrNull(): UploadFileRequest? {
        if (this.uploadType != DriveOutboxUploader.UploadNewFile) return null
        return try {
            OdinSystemSerializer.deserialize<UploadFileRequest>(this.json.decodeToString())
        } catch (_: Throwable) {
            null
        }
    }

    /** Back [SeedableMessageLookup.fileLookup] with the fixture's real DriveMainIndex. */
    private fun SeedableMessageLookup.backFilesWith(fixture: ChatMessageSenderServiceTestFixture) {
        fileLookup = { uid ->
            fixture.dbm.driveMainIndex.selectHomebaseFileByUnique(
                fixture.testIdentityId, fixture.chatDriveId, uid,
            )
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
            messageLookup.backFilesWith(fixture)

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

    /**
     * Regression for the "Unable to display this poll" bug: a typed (raw-header)
     * kind stores its descriptor JSON VERBATIM in appData.content. updateMessage
     * must write it straight through — routing it through the MessageAppData
     * envelope builder strips the descriptor's required fields, so the next read
     * threw MissingFieldException(question, options) and rendered the
     * unsupported-format chip.
     *
     * Asserts the optimistically-written (UNencrypted) header content re-parses to
     * a valid, now-closed [PollDescriptor].
     */
    @Test
    fun `updateMessage on a Poll writes the raw descriptor so it re-parses to a closed Poll`() = runTest {
        val messageLookup = SeedableMessageLookup()

        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(
                messageLookup = { _: DatabaseManager -> messageLookup },
            )
            messageLookup.backFilesWith(fixture)

            val conversationId = fixture.seedConversation(others = listOf("bob.test"))
            val messageId = Uuid.random()
            val versionTag = Uuid.random()
            val keyHeader = KeyHeader.newRandom16()

            val descriptor = PollDescriptor(
                question = "Best day?",
                options = listOf("Monday", "Tuesday"),
            )

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
                    messageContent = MessageContent.Poll(descriptor),
                )
            )

            service.updateMessage(
                messageId = messageId,
                versionTag = versionTag,
                content = OdinSystemSerializer.serialize(descriptor.copy(closed = true)),
            )

            // The optimistic write stores the UNencrypted header content. For a typed
            // kind that must be the raw descriptor (not the MessageAppData envelope),
            // so it re-parses to a valid, now-closed Poll.
            val storedContent = fixture.dbm.driveMainIndex
                .selectHomebaseFileByUnique(fixture.testIdentityId, fixture.chatDriveId, messageId)
                ?.fileMetadata?.appData?.content
            assertNotNull(storedContent, "updated Poll must have header content")

            val reparsed = OdinSystemSerializer.deserialize<PollDescriptor>(storedContent)
            assertTrue(reparsed.isValid(), "re-parsed Poll descriptor must be valid")
            assertTrue(reparsed.closed, "updated descriptor must be closed")
            assertEquals(descriptor.question, reparsed.question)
            assertEquals(descriptor.options, reparsed.options)
        }
    }

    // -----------------------------------------------------------------------
    // Pending-create (coalesce) path
    // -----------------------------------------------------------------------

    /**
     * The other `updateMessage` branch: when the original create is still queued in
     * the outbox, the edit coalesces into that create. This path has the same
     * raw-vs-envelope content choice as the normal path, so it must ALSO write the
     * raw descriptor (not the envelope) for a typed kind. Sends a Poll without
     * draining so the create stays pending, then ends it and asserts the
     * optimistically-written header content re-parses to a closed Poll.
     */
    @Test
    fun `updateMessage coalesces a closed Poll into a pending create as raw header content`() = runTest {
        val messageLookup = SeedableMessageLookup()

        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(
                messageLookup = { _: DatabaseManager -> messageLookup },
            )
            messageLookup.backFilesWith(fixture)

            val conversationId = fixture.seedConversation(others = listOf("bob.test"))
            val messageId = Uuid.random()
            val versionTag = Uuid.random()
            val keyHeader = KeyHeader.newRandom16()

            val descriptor = PollDescriptor(
                question = "Best day?",
                options = listOf("Monday", "Tuesday"),
            )

            // 1. Send the poll as a new typed message but DON'T drain — the create
            //    stays pending in the outbox, so updateMessage takes the coalesce path.
            service.sendNewTypedMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                content = MessageContent.Poll(descriptor),
                previousMessageUniqueId = null,
            )

            // 2. Seed the lookup so updateMessage finds the message (versionTag match +
            //    messageContent = Poll for dataType derivation).
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
                    isPendingSend = true,
                    versionTag = versionTag,
                    messageAppData = MessageAppData(),
                    reactionPreview = null,
                    previewThumbnail = null,
                    payloads = persistentListOf(),
                    keyHeader = keyHeader,
                    hasMore = false,
                    messageContent = MessageContent.Poll(descriptor),
                )
            )

            // 3. End the poll → coalesce into the pending create.
            service.updateMessage(
                messageId = messageId,
                versionTag = versionTag,
                content = OdinSystemSerializer.serialize(descriptor.copy(closed = true)),
            )

            // 4. The coalesced create's optimistically-written header content must be
            //    the raw closed descriptor, not the MessageAppData envelope.
            val storedContent = fixture.dbm.driveMainIndex
                .selectHomebaseFileByUnique(fixture.testIdentityId, fixture.chatDriveId, messageId)
                ?.fileMetadata?.appData?.content
            assertNotNull(storedContent, "coalesced create must have header content")

            val reparsed = OdinSystemSerializer.deserialize<PollDescriptor>(storedContent)
            assertTrue(reparsed.isValid(), "re-parsed Poll descriptor must be valid")
            assertTrue(reparsed.closed, "coalesced descriptor must be closed")
            assertEquals(descriptor.options, reparsed.options)
        }
    }

    // -----------------------------------------------------------------------
    // #900 — an edit must not move appData.userDate
    // -----------------------------------------------------------------------

    /**
     * Regression for #900 (conversation-list preview blanks after editing the
     * latest message): `MessageUiModel.userDate` is CLAMPED
     * (`minOf(rawUserDate, authorSpecificDate)` in `MessageMapper.mapToMessageData`),
     * so re-stamping the edit's `appData.userDate` from the model can LOWER it
     * below the SQL `DriveMainIndex.userDate`. The preview-refresh guards then
     * reject the edit re-emit and the overview keeps the blank placeholder.
     *
     * `updateMessage` must stamp the original un-clamped `appData.userDate` read
     * from the header file, on both the enqueued update request and the
     * optimistic DB write.
     */
    @Test
    fun `updateMessage preserves the original unclamped userDate, not the clamped model value`() = runTest {
        val messageLookup = SeedableMessageLookup()

        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(
                messageLookup = { _: DatabaseManager -> messageLookup },
            )
            messageLookup.backFilesWith(fixture)

            val conversationId = fixture.seedConversation(others = listOf("bob.test"))
            val messageId = Uuid.random()
            val versionTag = Uuid.random()
            val keyHeader = KeyHeader.newRandom16()
            val originalUserDate = 2_000L
            val clampedUserDate = 1_000L

            // The stored header carries the true (un-clamped) userDate.
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
                        dataType = 0,
                        userDate = originalUserDate,
                        content = OdinSystemSerializer.serialize(MessageAppData()),
                    ),
                ),
                originalRecipientCount = 1,
                fileSystemType = FileSystemType.Standard,
            )

            // The model the edit reads carries the clamped (lower) display value.
            messageLookup.seed(
                MessageUiModel(
                    id = messageId,
                    globalTransitId = null,
                    fileId = Uuid.random(),
                    conversationId = conversationId,
                    content = "original",
                    userDate = Instant.fromEpochMilliseconds(clampedUserDate),
                    modified = null,
                    created = Instant.fromEpochMilliseconds(clampedUserDate),
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
                    messageContent = null,
                )
            )

            service.updateMessage(
                messageId = messageId,
                versionTag = versionTag,
                content = "edited",
            )

            val rows = fixture.drainOutboxInDependencyOrder()
            val updateRow = rows.mapNotNull { it.asUpdateFileRequestOrNull() }.firstOrNull()
            assertNotNull(updateRow, "an UpdateFileByUniqueIdRequest must be enqueued after updateMessage")
            assertEquals(
                originalUserDate,
                updateRow.metadata.appData.userDate,
                "the enqueued edit must keep the original un-clamped userDate (#900)",
            )

            val storedUserDate = fixture.dbm.driveMainIndex
                .selectHomebaseFileByUnique(fixture.testIdentityId, fixture.chatDriveId, messageId)
                ?.fileMetadata?.appData?.userDate
            assertEquals(
                originalUserDate,
                storedUserDate,
                "the optimistic write must keep the SQL userDate invariant across an edit (#900)",
            )
        }
    }

    /**
     * Same #900 invariant on the pending-create coalesce branch: editing a
     * message whose create is still queued must not re-stamp the create's
     * `appData.userDate` from the clamped model value.
     */
    @Test
    fun `updateMessage coalescing into a pending create preserves the original userDate`() = runTest {
        val messageLookup = SeedableMessageLookup()

        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(
                messageLookup = { _: DatabaseManager -> messageLookup },
            )
            messageLookup.backFilesWith(fixture)

            val conversationId = fixture.seedConversation(others = listOf("bob.test"))
            val messageId = Uuid.random()
            val versionTag = Uuid.random()
            val keyHeader = KeyHeader.newRandom16()
            val originalUserDate = 2_000L
            val clampedUserDate = 1_000L

            // 1. Send with an explicit userDate but DON'T drain — the create stays
            //    pending in the outbox, so updateMessage takes the coalesce path.
            service.sendNewMessage(
                messageUniqueId = messageId,
                conversationId = conversationId,
                messageText = "original",
                previousMessageUniqueId = null,
                payloadBundle = null,
                userDate = UnixTimeUtc(originalUserDate),
            )

            // 2. The model the edit reads carries the clamped (lower) display value.
            messageLookup.seed(
                MessageUiModel(
                    id = messageId,
                    globalTransitId = null,
                    fileId = Uuid.random(),
                    conversationId = conversationId,
                    content = "original",
                    userDate = Instant.fromEpochMilliseconds(clampedUserDate),
                    modified = null,
                    created = Instant.fromEpochMilliseconds(clampedUserDate),
                    originalAuthor = OdinId(fixture.testDomain),
                    sender = OdinId(fixture.testDomain),
                    displayName = fixture.testDomain,
                    isDeleted = false,
                    isPendingSend = true,
                    versionTag = versionTag,
                    messageAppData = MessageAppData(),
                    reactionPreview = null,
                    previewThumbnail = null,
                    payloads = persistentListOf(),
                    keyHeader = keyHeader,
                    hasMore = false,
                    messageContent = null,
                )
            )

            // 3. Edit → coalesce into the pending create.
            service.updateMessage(
                messageId = messageId,
                versionTag = versionTag,
                content = "edited",
            )

            // 4. Both the amended create in the outbox and the optimistic DB row
            //    must keep the original userDate.
            val rows = fixture.drainOutboxInDependencyOrder()
            val createRow = rows
                .mapNotNull { it.asUploadFileRequestOrNull() }
                .firstOrNull { it.metadata.appData.uniqueId == messageId }
            assertNotNull(createRow, "the amended create must still be in the outbox")
            assertEquals(
                originalUserDate,
                createRow.metadata.appData.userDate,
                "the coalesced create must keep the original un-clamped userDate (#900)",
            )

            val storedUserDate = fixture.dbm.driveMainIndex
                .selectHomebaseFileByUnique(fixture.testIdentityId, fixture.chatDriveId, messageId)
                ?.fileMetadata?.appData?.userDate
            assertEquals(
                originalUserDate,
                storedUserDate,
                "the optimistic write must keep the SQL userDate invariant across a coalesced edit (#900)",
            )
        }
    }

    /**
     * Editing the caption of a delivered image must not touch the image (#1155).
     *
     * The enqueued update is a delta: a media key absent from the manifest is
     * preserved server-side, and only `dflt_key` (the text-overflow payload) is
     * ever listed for deletion. So the media key must appear nowhere — neither as
     * an AppendOrOverwrite (which would re-upload the bytes) nor as a
     * DeletePayload (which would destroy the image).
     *
     * This is also what makes the `isCreate = false` progress gate safe: chat's
     * edit path enqueues an UpdateFile row that never carries media, so its byte
     * progress must not drive the media upload overlay.
     */
    @Test
    fun `updateMessage on a delivered image edits the caption without re-uploading or deleting the media`() = runTest {
        val messageLookup = SeedableMessageLookup()
        val mediaKey = "chat_web0"

        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(
                messageLookup = { _: DatabaseManager -> messageLookup },
            )
            messageLookup.backFilesWith(fixture)

            val conversationId = fixture.seedConversation(others = listOf("bob.test"))
            val messageId = Uuid.random()
            val versionTag = Uuid.random()
            val keyHeader = KeyHeader.newRandom16()

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
                        userDate = 1_000L,
                        content = "original caption",
                    ),
                ),
                originalRecipientCount = 1,
                fileSystemType = FileSystemType.Standard,
            )

            messageLookup.seed(
                MessageUiModel(
                    id = messageId,
                    globalTransitId = null,
                    fileId = Uuid.random(),
                    conversationId = conversationId,
                    content = "original caption",
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
                    payloads = persistentListOf(
                        PayloadDescriptor(key = mediaKey, contentType = "image/jpeg"),
                    ),
                    keyHeader = keyHeader,
                    hasMore = false,
                    messageContent = null,
                )
            )

            service.updateMessage(
                messageId = messageId,
                versionTag = versionTag,
                content = "edited caption",
            )

            val rows = fixture.drainOutboxInDependencyOrder()
            val updateRow = rows.mapNotNull { it.asUpdateFileRequestOrNull() }.firstOrNull()
            assertNotNull(updateRow, "a caption edit on a delivered message must enqueue an UpdateFile row")

            val manifestKeys = updateRow.instructions.manifest?.payloadDescriptors.orEmpty()
            assertTrue(
                manifestKeys.none { it.payloadKey == mediaKey },
                "the media key must not appear in the update manifest — found ${manifestKeys.map { it.payloadKey to it.operationType }}",
            )
            // Pins that the edit touches ONE key and it is the text-overflow one, so
            // the assertion above can't pass just because the manifest came out empty.
            assertEquals(
                listOf(ChatProtocol.DefaultPayloadKey),
                manifestKeys.map { it.payloadKey },
                "a short caption edit must reference only the text-overflow payload",
            )
            assertTrue(
                updateRow.payloads.orEmpty().none { it.key == mediaKey },
                "the update must not re-send the media bytes",
            )
        }
    }
}
