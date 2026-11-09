package id.homebase.chat.services

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.common.BatchResult
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.chat.contactcard.ContactCardDescriptor
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.PayloadSizePolicy
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.ServerMetadata
import id.homebase.api.client.drives.files.AppFileMetaData
import id.homebase.api.client.drives.files.FileMetadata
import id.homebase.upload.PayloadBundle
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.Base64Bytes
import id.homebase.upload.PayloadBundleEncryptor
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * A contact card is the first typed kind with `allowForward = true`, and a typed kind stores its
 * descriptor VERBATIM in `appData.content`. `MessageAppData` has a default for every field and the
 * serializer ignores unknown keys, so re-wrapping the descriptor in that envelope succeeds silently
 * and lands a card with no `displayName` — which fails `ContactCardDescriptor`'s required-field
 * decode and paints the receiver's unsupported-format chip instead of the card.
 */
class ChatMessageSenderServiceForwardTest {

    private class DbBackedLookup : MessageLookup {
        var fileLookup: suspend (Uuid) -> HomebaseFile? = { null }

        override suspend fun getMessage(messageId: Uuid): MessageUiModel? = null

        override suspend fun getMessageFile(messageId: Uuid): HomebaseFile? = fileLookup(messageId)

        override suspend fun getMessages(
            messageIds: List<Uuid>,
            conversationId: Uuid,
        ): BatchResult<MessageUiModel> =
            BatchResult(records = emptyList(), hasMoreRows = false, cursor = QueryBatchCursor())

        override suspend fun loadFullMessage(conversationId: Uuid, messageId: Uuid): String? = null

        override fun findCachedFileId(messageId: Uuid): Uuid? = null
    }

    private suspend fun ChatMessageSenderServiceTestFixture.headerContentOf(uniqueId: Uuid): String? =
        dbm.driveMainIndex
            .selectHomebaseFileByUnique(testIdentityId, chatDriveId, uniqueId)
            ?.fileMetadata?.appData?.content

    @Test
    fun `forwarding a contact card carries the descriptor itself, not a MessageAppData envelope`() = runTest {
        val lookup = DbBackedLookup()

        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(messageLookup = { _: DatabaseManager -> lookup })
            lookup.fileLookup = { uid ->
                fixture.dbm.driveMainIndex
                    .selectHomebaseFileByUnique(fixture.testIdentityId, fixture.chatDriveId, uid)
            }

            val source = fixture.seedConversation(others = listOf("bob.test"))
            val target = fixture.seedConversation(others = listOf("carol.test"))
            val messageId = Uuid.random()
            val descriptor = ContactCardDescriptor(
                displayName = "Ada Vance",
                phones = listOf("+14155550123"),
                emails = listOf("ada@example.com"),
            )

            service.sendNewTypedMessage(
                messageUniqueId = messageId,
                conversationId = source,
                content = MessageContent.ContactCard(descriptor),
                previousMessageUniqueId = null,
            )

            val forwarded = service.forwardMessage(messageId, listOf(target))

            val storedContent = fixture.headerContentOf(forwarded.single().uniqueId)
            assertNotNull(storedContent, "the forwarded card must have header content")

            val reparsed = MessageContentParser.parse(
                ChatProtocol.ChatContactCardMessageDataType,
                storedContent,
            )
            assertEquals(
                descriptor,
                (reparsed as? MessageContent.ContactCard)?.descriptor,
                "a forwarded contact card must re-parse to the card that was forwarded",
            )
        }
    }

    /**
     * The other side of the branch: a text message's content IS a MessageAppData, and forwarding it
     * has to rebuild that envelope rather than copy it — a forward does not carry the quote of the
     * message it came from.
     */
    @Test
    fun `forwarding a text reply still rebuilds the envelope, dropping the quote`() =
        runTest {
            val lookup = DbBackedLookup()

            ChatMessageSenderServiceTestFixture().use { fixture ->
                val service = fixture.build(messageLookup = { _: DatabaseManager -> lookup })
                lookup.fileLookup = { uid ->
                    fixture.dbm.driveMainIndex
                        .selectHomebaseFileByUnique(fixture.testIdentityId, fixture.chatDriveId, uid)
                }

                val source = fixture.seedConversation(others = listOf("bob.test"))
                val target = fixture.seedConversation(others = listOf("carol.test"))
                val messageId = Uuid.random()

                service.replyToMessage(
                    messageUniqueId = messageId,
                    conversationId = source,
                    replyTo = ReplyPreview(
                        replyUniqueId = Uuid.random(),
                        authorOdinId = "bob.test",
                        message = "what is her number?",
                    ),
                    messageText = "here is the number",
                    previousMessageUniqueId = null,
                    payloadBundle = null,
                )

                val forwarded = service.forwardMessage(messageId, listOf(target))

                val storedContent = fixture.headerContentOf(forwarded.single().uniqueId)
                assertNotNull(storedContent, "the forwarded text must have header content")

                val envelope = OdinSystemSerializer.deserialize<MessageAppData>(storedContent)
                assertEquals("here is the number", envelope.getMessage())
                assertNull(envelope.replyPreview, "a forward is not a reply to the original's parent")
            }
        }

    @Test
    fun `forwarding a view-once message throws and enqueues nothing`() = runTest {
        val lookup = DbBackedLookup()

        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(messageLookup = { _: DatabaseManager -> lookup })
            lookup.fileLookup = { uid ->
                fixture.dbm.driveMainIndex
                    .selectHomebaseFileByUnique(fixture.testIdentityId, fixture.chatDriveId, uid)
            }

            val source = fixture.seedConversation(others = listOf("bob.test"))
            val target = fixture.seedConversation(others = listOf("carol.test"))
            val messageId = Uuid.random()

            service.sendNewTypedMessage(
                messageUniqueId = messageId,
                conversationId = source,
                content = MessageContent.ViewOnce(
                    id.homebase.chat.viewonce.ViewOnceDescriptor(id.homebase.chat.viewonce.ViewOnceDescriptor.KIND_IMAGE)
                ),
                previousMessageUniqueId = null,
            )
            val rowsBefore = fixture.outboxRowCount()

            val failure = runCatching { service.forwardMessage(messageId, listOf(target)) }.exceptionOrNull()

            assertIs<IllegalArgumentException>(failure)
            assertEquals(rowsBefore, fixture.outboxRowCount(), "nothing may be enqueued for the target")
        }
    }

    private class RecordingEncryptor : PayloadBundleEncryptor {
        val seen = mutableListOf<Map<String, ByteArray>>()
        override suspend fun encryptBundle(
            uniqueId: Uuid,
            bundle: PayloadBundle?,
            aesKey: SecureByteArray,
            scope: CoroutineScope,
        ): PayloadBundle {
            seen += bundle?.payloads.orEmpty().associate { it.key to File(it.filePath).readBytes() }
            return PayloadBundle(payloads = emptyList(), thumbnails = emptyList(), previewThumbs = emptyList())
        }
    }

    private class MediaForwardFixture(val sizeBytes: Int) {
        val sourceKey = KeyHeader.newRandom16()
        val iv = ByteArray(16) { (it + 1).toByte() }
        val plaintext = ByteArray(sizeBytes).also { Random.nextBytes(it) }
        val fileOps = DiskFileOperationsProvider()
        val encryptor = RecordingEncryptor()
        var payloadGets = 0
        var thumbGets = 0
        private var cachedBody: ByteArray? = null
        private suspend fun body(): ByteArray =
            cachedBody ?: KeyHeader(iv = iv, aesKey = sourceKey.aesKey).encryptDataAes(plaintext).also { cachedBody = it }

        val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
            if (request.url.encodedPath.contains("thumb")) {
                thumbGets++
                respond(ByteArray(8) { 7 }, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/webp"))
            } else {
                payloadGets++
                respond(body(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
            }
        }

        fun sourceFile(chatDriveId: Uuid, withThumb: Boolean, content: String): HomebaseFile = HomebaseFile(
            fileId = Uuid.random(),
            driveId = chatDriveId,
            fileState = FileState.Active,
            fileSystemType = FileSystemType.Standard,
            keyHeader = sourceKey,
            fileMetadata = FileMetadata(
                appData = AppFileMetaData(content = content, dataType = 0, groupId = Uuid.random()),
                payloads = listOf(
                    PayloadDescriptor(
                        key = "chat_web0",
                        contentType = "video/mp4",
                        iv = Base64Bytes(iv),
                        thumbnails = if (withThumb) listOf(
                            ThumbnailDescriptor(pixelWidth = 10, pixelHeight = 10, contentType = "image/webp")
                        ) else null,
                    )
                ),
            ),
            serverMetadata = ServerMetadata(),
        )
    }

    private suspend fun forwardMedia(
        media: MediaForwardFixture,
        targets: List<List<String>>,
        withThumb: Boolean = false,
    ) {
        val lookup = DbBackedLookup()
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(
                messageLookup = { _: DatabaseManager -> lookup },
                encryptorOverride = media.encryptor,
                engineHandler = media.handler,
                fileOps = media.fileOps,
            )
            val content = OdinSystemSerializer.serialize(MessageAppData(message = JsonPrimitive("clip")))
            val source = media.sourceFile(fixture.chatDriveId, withThumb, content)
            lookup.fileLookup = { source }
            val targetIds = targets.map { fixture.seedConversation(others = it) }

            val forwarded = service.forwardMessage(Uuid.random(), targetIds)

            assertEquals(targets.size, forwarded.size)
            assertEquals(targets.size.toLong(), fixture.outboxRowCount())
        }
    }

    @Test
    fun `forwarding a payload over the render limit streams it instead of reading it whole`() = runTest {
        val media = MediaForwardFixture(PayloadSizePolicy.RENDER_LIMIT_BYTES.toInt() + 1)

        forwardMedia(media, listOf(listOf("carol.test")))

        assertEquals(1, media.encryptor.seen.size)
        assertContentEquals(media.plaintext, media.encryptor.seen.single().getValue("chat_web0"))
    }

    @Test
    fun `a large forward to a group and a 1-1 hands every target the full file`() = runTest {
        val media = MediaForwardFixture(PayloadSizePolicy.RENDER_LIMIT_BYTES.toInt() + 1)

        forwardMedia(media, listOf(listOf("carol.test"), listOf("dave.test", "erin.test")))

        assertEquals(2, media.encryptor.seen.size)
        media.encryptor.seen.forEach { assertContentEquals(media.plaintext, it.getValue("chat_web0")) }
        assertEquals(1, media.payloadGets, "the payload is downloaded once and shared by the targets")
    }

    @Test
    fun `a small payload is streamed to disk too and never held as a byte array`() = runTest {
        val media = MediaForwardFixture(20_000)

        forwardMedia(media, listOf(listOf("carol.test")), withThumb = true)

        assertContentEquals(media.plaintext, media.encryptor.seen.single().getValue("chat_web0"))
        assertEquals(emptyList(), media.fileOps.inMemoryWrites.filter { it.startsWith("fwd_") })
        assertEquals(1, media.thumbGets, "the thumbnail still travels with the forward")
    }
}
