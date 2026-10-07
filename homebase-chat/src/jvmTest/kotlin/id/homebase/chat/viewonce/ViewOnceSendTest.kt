package id.homebase.chat.viewonce

import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.image.MediaQuality
import id.homebase.chat.services.ChatMessageSenderServiceTestFixture
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import id.homebase.upload.PayloadBundle
import id.homebase.upload.PayloadBundleEncryptor
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.uuid.Uuid

class ViewOnceSendTest {

    /** Stands in for the real encryptor, and for the video processor when [simulateVideoProcessor]. */
    private class PassThroughEncryptor(private val simulateVideoProcessor: Boolean) : PayloadBundleEncryptor {
        override suspend fun encryptBundle(
            uniqueId: Uuid,
            bundle: PayloadBundle?,
            aesKey: SecureByteArray,
            scope: CoroutineScope,
        ): PayloadBundle {
            val b = bundle ?: return PayloadBundle(emptyList(), emptyList(), emptyList())
            if (!simulateVideoProcessor) return b
            return PayloadBundle(
                payloads = b.payloads.map {
                    it.copy(previewThumbnail = EmbeddedThumb(8, 8, "image/jpeg", "AAAA"))
                },
                thumbnails = b.payloads.map { ThumbnailFile(320, 240, ByteArray(4) { 1 }, it.key) },
                previewThumbs = emptyList(),
            )
        }
    }

    private fun jpegFile(): String {
        val file = File.createTempFile("V1-viewonce", ".jpg").apply { deleteOnExit() }
        val image = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 640) for (y in 0 until 480) image.setRGB(x, y, (x * 31 + y * 17) and 0xFFFFFF)
        ImageIO.write(image, "jpg", file)
        return file.absolutePath
    }

    @Test
    fun `view-once image is stored as dataType 216 with one chat_web0 payload and no thumbnails`() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(encryptorOverride = PassThroughEncryptor(simulateVideoProcessor = false))
            val conversation = fixture.seedConversation(others = listOf("bob.test"))
            val messageId = Uuid.random()

            service.sendAttachmentsMessage(
                viewOnce = true,
                messageId = messageId,
                conversationId = conversation,
                text = "a caption that must be ignored",
                attachments = listOf(AttachmentInput(filePath = jpegFile(), contentType = "image/jpeg")),
                replyTo = null,
                sentAt = UnixTimeUtc.now(),
                fileOperationsProvider = JvmFileOperationsProvider(),
                mediaQuality = MediaQuality.STANDARD,
            )

            val stored = fixture.dbm.driveMainIndex
                .selectHomebaseFileByUnique(fixture.testIdentityId, fixture.chatDriveId, messageId)
            assertNotNull(stored)
            val appData = stored.fileMetadata.appData
            assertEquals(216, appData.dataType)
            assertTrue(!appData.content.isNullOrBlank())
            val parsed = assertIs<MessageContent.ViewOnce>(
                MessageContentParser.parse(appData.dataType, appData.content)
            )
            assertEquals(ViewOnceDescriptor.KIND_IMAGE, parsed.descriptor?.kind)
            assertNull(appData.previewThumbnail, "header preview thumbnail must be null")

            val payloads = stored.fileMetadata.payloads.orEmpty()
            assertEquals(listOf("${ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB}0"), payloads.map { it.key })
            assertTrue(payloads.single().thumbnails.isNullOrEmpty(), "image payload must carry no thumbnails")
            assertNull(payloads.single().previewThumbnail)
        }
    }

    @Test
    fun `view-once video drops the thumbnails the video processor produces`() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(encryptorOverride = PassThroughEncryptor(simulateVideoProcessor = true))
            val conversation = fixture.seedConversation(others = listOf("bob.test"))
            val messageId = Uuid.random()

            service.sendAttachmentsMessage(
                viewOnce = true,
                messageId = messageId,
                conversationId = conversation,
                text = "",
                attachments = listOf(AttachmentInput(filePath = "/tmp/V1-clip.mp4", contentType = "video/mp4")),
                replyTo = null,
                sentAt = UnixTimeUtc.now(),
                fileOperationsProvider = JvmFileOperationsProvider(),
                mediaQuality = MediaQuality.STANDARD,
            )

            val stored = fixture.dbm.driveMainIndex
                .selectHomebaseFileByUnique(fixture.testIdentityId, fixture.chatDriveId, messageId)
            assertNotNull(stored)
            assertEquals(216, stored.fileMetadata.appData.dataType)
            assertNull(stored.fileMetadata.appData.previewThumbnail)
            val payload = stored.fileMetadata.payloads.orEmpty().single()
            assertEquals("chat_web0", payload.key)
            assertTrue(payload.thumbnails.isNullOrEmpty(), "video poster thumbnails must be dropped")
            assertNull(payload.previewThumbnail)
            assertEquals(
                ViewOnceDescriptor.KIND_VIDEO,
                (MessageContentParser.parse(216, stored.fileMetadata.appData.content) as MessageContent.ViewOnce)
                    .descriptor?.kind,
            )
        }
    }

    @Test
    fun `the same helper with viewOnce false still sends a plain message`() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(encryptorOverride = PassThroughEncryptor(simulateVideoProcessor = false))
            val conversation = fixture.seedConversation(others = listOf("bob.test"))
            val messageId = Uuid.random()

            service.sendAttachmentsMessage(
                viewOnce = false,
                messageId = messageId,
                conversationId = conversation,
                text = "look",
                attachments = listOf(AttachmentInput(filePath = jpegFile(), contentType = "image/jpeg")),
                replyTo = null,
                sentAt = UnixTimeUtc.now(),
                fileOperationsProvider = JvmFileOperationsProvider(),
                mediaQuality = MediaQuality.STANDARD,
            )

            val stored = fixture.dbm.driveMainIndex
                .selectHomebaseFileByUnique(fixture.testIdentityId, fixture.chatDriveId, messageId)
            assertNotNull(stored)
            assertEquals(0, stored.fileMetadata.appData.dataType)
            assertNotNull(stored.fileMetadata.appData.previewThumbnail, "a normal image keeps its preview")
        }
    }

    @Test
    fun `view-once refuses two attachments and non-media`() = runTest {
        ChatMessageSenderServiceTestFixture().use { fixture ->
            val service = fixture.build(encryptorOverride = PassThroughEncryptor(simulateVideoProcessor = false))
            val conversation = fixture.seedConversation(others = listOf("bob.test"))
            val image = AttachmentInput(filePath = jpegFile(), contentType = "image/jpeg")

            for (attachments in listOf(
                listOf(image, image),
                listOf(AttachmentInput(filePath = "/tmp/V1-a.pdf", contentType = "application/pdf")),
                listOf(image.copy(forceSticker = true)),
            )) {
                var threw = false
                try {
                    service.sendAttachmentsMessage(
                        viewOnce = true,
                        messageId = Uuid.random(),
                        conversationId = conversation,
                        text = "",
                        attachments = attachments,
                        replyTo = null,
                        sentAt = UnixTimeUtc.now(),
                        fileOperationsProvider = JvmFileOperationsProvider(),
                        mediaQuality = MediaQuality.STANDARD,
                    )
                } catch (_: IllegalArgumentException) {
                    threw = true
                }
                assertTrue(threw)
            }
            assertEquals(0L, fixture.outboxRowCount())
        }
    }
}
