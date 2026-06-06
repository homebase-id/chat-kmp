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
 * Pins the sticker auto-detection in MessageAttachmentBuilder's image branch:
 * a transparent PNG produces a {"isSticker":true} descriptor, an opaque JPEG keeps
 * the legacy "" descriptor, and forceSticker overrides detection. This is the
 * send-side half of the sticker feature (the render-side reads descriptorInfo()).
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

    @Test
    fun transparentPng_setsStickerDescriptor() = runTest {
        val path = "/tmp/cutout.png"
        val bundle = MessageAttachmentBuilder.buildSingle(
            attachment = AttachmentInput(filePath = path, contentType = "image/png"),
            fileOperationsProvider = fakeFsServing(path, transparentPng()),
            payloadKey = "chat_web0",
        )
        val payload = bundle.payloads.single()
        assertEquals(true, stickerOf(payload.descriptorContent))
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
    fun forceSticker_overridesDetection_onOpaqueImage() = runTest {
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
    fun transparentPng_descriptorParsesAsStickerImageFile() = runTest {
        // End-to-end: the descriptor written at send must round-trip to ImageFile(isSticker=true).
        val path = "/tmp/cutout2.png"
        val bundle = MessageAttachmentBuilder.buildSingle(
            attachment = AttachmentInput(filePath = path, contentType = "image/png"),
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
}
