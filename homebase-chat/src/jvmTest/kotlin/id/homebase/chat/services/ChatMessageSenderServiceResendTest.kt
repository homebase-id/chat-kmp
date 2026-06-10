package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.PayloadDeleteKey
import id.homebase.api.client.drives.upload.PayloadOperationType
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.common.BatchResult
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.chat.data.MessageUiModel
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ChatMessageSenderService.resendMessage] — retrying a permanently-failed
 * send. Uses the real in-memory DB + outbox; the by-uid server check is
 * scripted through the fixture's mock engine (404 = server absent → the retry
 * must be a create).
 */
@OptIn(ExperimentalEncodingApi::class)
class ChatMessageSenderServiceResendTest {

    /**
     * [MessageLookup] backed by the fixture's real DriveMainIndex, so the
     * optimistic write → mark-failed → resend chain reads the same rows
     * production would. [getMessage] rebuilds the [MessageUiModel] from the
     * stored plaintext header content (the optimistic write stores the
     * UNencrypted metadata).
     */
    private class DbBackedMessageLookup(
        private val dbm: DatabaseManager,
        private val identityId: Uuid,
        private val chatDriveId: Uuid,
        private val domain: String,
    ) : MessageLookup {
        override suspend fun getMessageFile(messageId: Uuid): HomebaseFile? =
            dbm.driveMainIndex.selectHomebaseFileByUnique(identityId, chatDriveId, messageId)

        override suspend fun getMessage(messageId: Uuid): MessageUiModel? {
            val file = getMessageFile(messageId) ?: return null
            val content = file.fileMetadata.appData.content ?: return null
            val appData = OdinSystemSerializer.deserialize<MessageAppData>(content)
            return MessageUiModel(
                id = messageId,
                globalTransitId = null,
                fileId = file.fileId,
                conversationId = file.fileMetadata.appData.groupId ?: return null,
                content = appData.getMessage(),
                userDate = Instant.fromEpochMilliseconds(file.fileMetadata.appData.userDate ?: 0L),
                modified = null,
                created = Instant.fromEpochMilliseconds(0L),
                originalAuthor = OdinId(domain),
                sender = OdinId(domain),
                displayName = domain,
                localReadTimestamp = null,
                isDeleted = false,
                isPendingSend = false,
                versionTag = file.fileMetadata.versionTag ?: Uuid.NIL,
                messageAppData = appData,
                reactionPreview = null,
                previewThumbnail = null,
                payloads = file.fileMetadata.payloads?.toImmutableList(),
                keyHeader = file.keyHeader,
                hasMore = false,
            )
        }

        override suspend fun getMessages(messageIds: List<Uuid>, conversationId: Uuid): BatchResult<MessageUiModel> =
            BatchResult(records = emptyList(), hasMoreRows = false, cursor = QueryBatchCursor())

        override suspend fun loadFullMessage(conversationId: Uuid, messageId: Uuid): String? = null

        override fun findCachedFileId(messageId: Uuid): Uuid? = null
    }

    private fun ChatMessageSenderServiceTestFixture.dbLookup() = { dbm: DatabaseManager ->
        DbBackedMessageLookup(dbm, testIdentityId, chatDriveId, testDomain)
    }

    private suspend fun ChatMessageSenderServiceTestFixture.localTags(messageId: Uuid): List<Uuid> =
        dbm.driveMainIndex.selectHomebaseFileByUnique(testIdentityId, chatDriveId, messageId)!!
            .fileMetadata.localAppData?.tags.orEmpty()

    /** Simulate the outbox permanently dropping the message: delete the row + swap to the failed tag. */
    private suspend fun ChatMessageSenderServiceTestFixture.dropAndMarkFailed(messageId: Uuid) {
        val drained = drainOutboxInDependencyOrder()
        assertTrue(drained.any { it.uniqueId == messageId }, "expected the create row in the outbox")
        optimisticWriter.updateLocalTags(
            chatDriveId,
            messageId,
            localTags(messageId).filterNot { it == ChatProtocol.isPendingSendTag } + ChatProtocol.isFailedSendTag,
        )
    }

    @Test
    fun `failed text send retries as a create with the original keyHeader and tags swap back to pending`() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(
                messageLookup = fixture.dbLookup(),
                engineHandler = { respondError(HttpStatusCode.NotFound) }, // server absent
            )
            val conversationId = fixture.seedConversation(others = listOf("bob.test"))
            val messageId = Uuid.random()

            service.sendNewMessage(messageId, conversationId, "hello", null, null)
            val originalKeyHeader = fixture.dbm.driveMainIndex
                .selectHomebaseFileByUnique(fixture.testIdentityId, fixture.chatDriveId, messageId)!!
                .keyHeader
            fixture.dropAndMarkFailed(messageId)
            assertEquals(0, fixture.outboxRowCount(), "drop must leave the outbox empty")

            val outcome = service.resendMessage(messageId)

            assertEquals(ResendOutcome.EnqueuedCreate, outcome)
            val rows = fixture.drainOutboxInDependencyOrder()
            assertEquals(1, rows.size)
            assertEquals(messageId, rows.single().uniqueId)
            assertEquals(DriveOutboxUploader.UploadNewFile, rows.single().uploadType)

            val request = OdinSystemSerializer.deserialize<UploadFileRequest>(rows.single().json.decodeToString())
            assertEquals(
                OdinSystemSerializer.serialize(originalKeyHeader),
                OdinSystemSerializer.serialize(request.keyHeader),
                "retry must reuse the original keyHeader — a fresh one would orphan existing payloads",
            )
            assertEquals(listOf("bob.test"), request.transitOptions?.recipients?.map { it.domainName })

            val tags = fixture.localTags(messageId)
            assertTrue(ChatProtocol.isPendingSendTag in tags, "bubble must flip back to Sending")
            assertFalse(ChatProtocol.isFailedSendTag in tags, "failed tag must clear on retry")
        }
    }

    @Test
    fun `a message still queued in the outbox is not re-enqueued`() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(
                messageLookup = fixture.dbLookup(),
                engineHandler = { respondError(HttpStatusCode.NotFound) },
            )
            val conversationId = fixture.seedConversation(others = listOf("bob.test"))
            val messageId = Uuid.random()
            service.sendNewMessage(messageId, conversationId, "hello", null, null)
            val rowsBefore = fixture.outboxRowCount()

            val outcome = service.resendMessage(messageId)

            assertEquals(ResendOutcome.AlreadyQueued, outcome)
            assertEquals(rowsBefore, fixture.outboxRowCount(), "no extra row may appear")
        }
    }

    /**
     * The graceful-cleanup scenario: a never-sent media message whose payload
     * bytes are gone (cache evicted, server absent). The retry must refuse —
     * enqueueing the header alone would silently deliver a text-only message.
     */
    @Test
    fun `unrecoverable media refuses to enqueue anything`() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(
                messageLookup = fixture.dbLookup(),
                engineHandler = { respondError(HttpStatusCode.NotFound) }, // server absent AND payload bytes 404
            )
            val conversationId = fixture.seedConversation(others = listOf("bob.test"))
            val messageId = Uuid.random()

            // Seed the failed media message directly: a file with a media payload
            // descriptor whose bytes exist nowhere (payload cache empty, server 404s).
            val appData = MessageAppData(message = JsonPrimitive("look at this pic"))
            fixture.optimisticWriter.writeNewFile(
                driveId = fixture.chatDriveId,
                keyHeader = KeyHeader.newRandom16(),
                unecryptedMetadata = UploadFileMetadata(
                    allowDistribution = true,
                    isEncrypted = true,
                    appData = UploadAppFileMetaData(
                        uniqueId = messageId,
                        groupId = conversationId,
                        fileType = ChatProtocol.MessageFileType,
                        userDate = 0L,
                        content = OdinSystemSerializer.serialize(appData),
                    ),
                ),
                originalRecipientCount = 1,
                fileSystemType = FileSystemType.Standard,
                payloadDescriptors = listOf(
                    PayloadDescriptor(
                        key = "img_key1",
                        contentType = "image/jpeg",
                        iv = Base64.encode(ByteArray(16) { 7 }),
                    ),
                ),
            )
            fixture.optimisticWriter.updateLocalTags(
                fixture.chatDriveId, messageId, listOf(ChatProtocol.isFailedSendTag),
            )

            val outcome = service.resendMessage(messageId)

            assertEquals(ResendOutcome.UnrecoverableMedia, outcome)
            assertEquals(0, fixture.outboxRowCount(), "nothing may be enqueued — no silent text-only send")
            assertTrue(
                ChatProtocol.isFailedSendTag in fixture.localTags(messageId),
                "the message must stay in the failed state",
            )
        }
    }

    @Test
    fun `a server error on the presence check fails the retry instead of guessing`() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(
                messageLookup = fixture.dbLookup(),
                engineHandler = { respondError(HttpStatusCode.InternalServerError) },
            )
            val conversationId = fixture.seedConversation(others = listOf("bob.test"))
            val messageId = Uuid.random()
            service.sendNewMessage(messageId, conversationId, "hello", null, null)
            fixture.dropAndMarkFailed(messageId)

            val outcome = service.resendMessage(messageId)

            assertTrue(outcome is ResendOutcome.Failed, "got $outcome")
            assertEquals(0, fixture.outboxRowCount())
            assertTrue(ChatProtocol.isFailedSendTag in fixture.localTags(messageId))
        }
    }

    // ---- update-path request shape (unit level — the server-present E2E needs
    // an encrypted ServerFile response, which the create-path tests don't) ----

    @Test
    fun `buildResendUpdateRequest stamps the server versionTag and ships recovered payloads`() = runTest {
        val driveId = Uuid.random()
        val messageId = Uuid.random()
        val serverVersionTag = Uuid.random()
        val keyHeader = KeyHeader.newRandom16()
        val recovered = PayloadFile(
            key = "img_key1",
            filePath = "/tmp/retry_img_key1_0.enc",
            contentType = "image/jpeg",
            isPreEncrypted = true,
            iv = ByteArray(16) { 9 },
        )
        val thumb = ThumbnailFile(
            pixelWidth = 100,
            pixelHeight = 100,
            thumbnailBytes = ByteArray(32),
            key = "img_key1",
        )

        val request = buildResendUpdateRequest(
            driveId = driveId,
            messageId = messageId,
            keyHeader = keyHeader,
            unencryptedMetadata = UploadFileMetadata(
                allowDistribution = true,
                isEncrypted = true,
                versionTag = serverVersionTag,
                appData = UploadAppFileMetaData(
                    uniqueId = messageId,
                    groupId = Uuid.random(),
                    fileType = ChatProtocol.MessageFileType,
                    userDate = 0L,
                    content = "{}",
                ),
            ),
            recipients = listOf(OdinId("bob.test")),
            payloads = listOf(recovered),
            toDeletePayloads = listOf(PayloadDeleteKey(ChatProtocol.DefaultPayloadKey)),
            thumbnails = listOf(thumb),
        )

        assertEquals(driveId, request.driveId)
        assertEquals(messageId, request.uniqueId)
        assertEquals(
            serverVersionTag, request.metadata.versionTag,
            "the update must carry the SERVER's current versionTag, not the stale local one",
        )
        assertEquals(UpdateLocale.Local, request.instructions.locale)
        assertEquals(listOf("bob.test"), request.instructions.recipients.map { it.domainName })
        assertEquals(listOf(recovered), request.payloads)
        assertEquals(listOf(thumb), request.thumbnails)
        val descriptors = request.instructions.manifest.payloadDescriptors.orEmpty()
        val append = descriptors.singleOrNull { it.operationType == PayloadOperationType.AppendOrOverwrite }
        assertNotNull(append, "the manifest must describe the recovered payload")
        assertEquals("img_key1", append.payloadKey)
        assertContentEquals(
            recovered.iv, append.iv,
            "the pre-encrypted payload's own iv must be preserved, not regenerated",
        )
        val delete = descriptors.singleOrNull { it.operationType == PayloadOperationType.DeletePayload }
        assertNotNull(delete, "the now-empty overflow must be deleted server-side")
        assertEquals(ChatProtocol.DefaultPayloadKey, delete.payloadKey)
    }
}
