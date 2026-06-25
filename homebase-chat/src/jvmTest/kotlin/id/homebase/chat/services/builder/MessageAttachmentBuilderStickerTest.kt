package id.homebase.chat.services.builder

import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.request.forms.InputProvider
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest

/**
 * Pins MessageAttachmentBuilder's image branch: a sticker is produced ONLY when
 * forceSticker is set. Transparency does NOT auto-sticker — a shared/normal transparent
 * PNG sends as a normal image (issue #854). This is the send-side half of the sticker
 * feature (the render-side reads descriptorInfo()).
 */
class MessageAttachmentBuilderStickerTest {

    /** Encode a w×h image, alpha provided per pixel, in the given format. */
    private fun encode(
        w: Int,
        h: Int,
        format: EncodedImageFormat,
        alphaAt: (x: Int, y: Int) -> Int,
    ): ByteArray {
        val info = ImageInfo(
            colorInfo = ColorInfo(ColorType.BGRA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB),
            width = w,
            height = h,
        )
        val rowBytes = w * 4
        val bytes = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                bytes[i] = 0                        // B
                bytes[i + 1] = (0x80).toByte()      // G
                bytes[i + 2] = (0xFF).toByte()      // R
                bytes[i + 3] = (alphaAt(x, y) and 0xFF).toByte() // A
            }
        }
        val image = Image.makeRaster(info, bytes, rowBytes)
        return image.encodeToData(format)?.bytes ?: error("encode failed")
    }

    private fun transparentPng(): ByteArray =
        encode(64, 64, EncodedImageFormat.PNG) { x, y -> if (x < 8 && y < 8) 0x00 else 0xFF }

    private fun opaqueJpeg(): ByteArray =
        encode(64, 64, EncodedImageFormat.JPEG) { _, _ -> 0xFF }

    /** Returns the bytes registered at [filePath]; thumbnail generation reads them. */
    private fun fakeFsServing(filePath: String, bytes: ByteArray) =
        object : FileOperationsProvider {
            override fun getCacheDirectory(): String = "/tmp/test-cache"
            override fun openFileInput(path: String): InputProvider = fail("openFileInput")
            override suspend fun readFileBytes(path: String): ByteArray {
                assertEquals(filePath, path)
                return bytes
            }
            override fun deleteTempFile(path: String): Boolean = false
            override fun getFileSize(path: String): Long = bytes.size.toLong()
            override suspend fun writeBytesToTempFile(
                bytes: ByteArray, prefix: String, suffix: String,
            ): String = fail("writeBytesToTempFile")
            override suspend fun writeBytesToShareOutboundFile(
                bytes: ByteArray, suffix: String,
            ): String = fail("writeBytesToShareOutboundFile")
            override suspend fun writeStream(path: String, data: Flow<ByteArray>) =
                fail<Unit>("writeStream")
            private fun <T> fail(name: String): T =
                throw UnsupportedOperationException("$name should not be called from this test")
        }

    private fun stickerOf(descriptorContent: String?): Boolean? {
        // Reconstruct the descriptor as a receiver would and read the flag back.
        val info = id.homebase.api.client.drives.files.PayloadDescriptor(
            key = "chat_web0",
            contentType = "image/png",
            descriptorContent = descriptorContent,
        ).descriptorInfo()
        return (info as? DescriptorContent.ImageFile)?.isSticker
    }

    /** Reconstruct the descriptor as a receiver would and read back the download filename. */
    private fun filenameOf(descriptorContent: String?): String? =
        id.homebase.api.client.drives.files.PayloadDescriptor(
            key = "chat_web0",
            contentType = "image/png",
            descriptorContent = descriptorContent,
        ).filename()

    @Test
    fun transparentPng_withoutForceSticker_isNotASticker() = runTest {
        // #854: transparency alone must NOT auto-sticker. A shared/normal transparent PNG
        // keeps the legacy "" descriptor and sends as a normal image.
        val path = "/tmp/cutout.png"
        val bundle = MessageAttachmentBuilder.buildSingle(
            attachment = AttachmentInput(filePath = path, contentType = "image/png"),
            fileOperationsProvider = fakeFsServing(path, transparentPng()),
            payloadKey = "chat_web0",
        )
        val payload = bundle.payloads.single()
        assertEquals("", payload.descriptorContent)
        assertEquals(false, stickerOf(payload.descriptorContent))
    }

    @Test
    fun opaqueJpeg_keepsEmptyDescriptor() = runTest {
        val path = "/tmp/photo.jpg"
        val bundle = MessageAttachmentBuilder.buildSingle(
            attachment = AttachmentInput(filePath = path, contentType = "image/jpeg"),
            fileOperationsProvider = fakeFsServing(path, opaqueJpeg()),
            payloadKey = "chat_web0",
        )
        val payload = bundle.payloads.single()
        // Legacy empty descriptor → non-sticker; nothing changes for ordinary photos.
        assertEquals("", payload.descriptorContent)
        assertEquals(false, stickerOf(payload.descriptorContent))
    }

    @Test
    fun forceSticker_producesSticker_onOpaqueImage() = runTest {
        val path = "/tmp/opaque.png"
        val bundle = MessageAttachmentBuilder.buildSingle(
            attachment = AttachmentInput(
                filePath = path,
                contentType = "image/png",
                forceSticker = true,
            ),
            fileOperationsProvider = fakeFsServing(
                path,
                encode(32, 32, EncodedImageFormat.PNG) { _, _ -> 0xFF },
            ),
            payloadKey = "chat_web0",
        )
        val payload = bundle.payloads.single()
        assertEquals(true, stickerOf(payload.descriptorContent))
    }

    @Test
    fun forceStickerPng_descriptorParsesAsStickerImageFile() = runTest {
        // End-to-end: a forceSticker send's descriptor must round-trip to ImageFile(isSticker=true).
        val path = "/tmp/cutout2.png"
        val bundle = MessageAttachmentBuilder.buildSingle(
            attachment = AttachmentInput(filePath = path, contentType = "image/png", forceSticker = true),
            fileOperationsProvider = fakeFsServing(path, transparentPng()),
            payloadKey = "chat_web0",
        )
        val descriptor = id.homebase.api.client.drives.files.PayloadDescriptor(
            key = "chat_web0",
            contentType = "image/png",
            descriptorContent = bundle.payloads.single().descriptorContent,
        )
        val info = descriptor.descriptorInfo()
        assertTrue(info is DescriptorContent.ImageFile && info.isSticker)
    }

    @Test
    fun forceStickerPng_downloadNameIsStickerFilePng() = runTest {
        // A forceSticker PNG downloads as StickerFile.png, not the sender's original
        // camera-roll name. The real detected extension is preserved.
        val path = "/tmp/IMG_1234.png"
        val bundle = MessageAttachmentBuilder.buildSingle(
            attachment = AttachmentInput(filePath = path, contentType = "image/png", forceSticker = true),
            fileOperationsProvider = fakeFsServing(path, transparentPng()),
            payloadKey = "chat_web0",
        )
        assertEquals("StickerFile.png", filenameOf(bundle.payloads.single().descriptorContent))
    }

    @Test
    fun forceStickerWebp_downloadNameIsStickerFileWebp() = runTest {
        // The "Send as sticker" toggle on an opaque WebP still names the download
        // StickerFile.webp — the real detected extension is preserved.
        val path = "/tmp/IMG_5678.webp"
        val bundle = MessageAttachmentBuilder.buildSingle(
            attachment = AttachmentInput(
                filePath = path,
                contentType = "image/webp",
                forceSticker = true,
            ),
            fileOperationsProvider = fakeFsServing(
                path,
                encode(32, 32, EncodedImageFormat.WEBP) { _, _ -> 0xFF },
            ),
            payloadKey = "chat_web0",
        )
        assertEquals("StickerFile.webp", filenameOf(bundle.payloads.single().descriptorContent))
    }

    @Test
    fun opaqueJpeg_keepsOriginalDownloadName() = runTest {
        // A non-sticker image carries no ImageFile descriptor, so filename() stays null and
        // the download falls back to the original/key as before — only stickers are renamed.
        val path = "/tmp/photo.jpg"
        val bundle = MessageAttachmentBuilder.buildSingle(
            attachment = AttachmentInput(filePath = path, contentType = "image/jpeg"),
            fileOperationsProvider = fakeFsServing(path, opaqueJpeg()),
            payloadKey = "chat_web0",
        )
        assertEquals(null, filenameOf(bundle.payloads.single().descriptorContent))
    }
}
