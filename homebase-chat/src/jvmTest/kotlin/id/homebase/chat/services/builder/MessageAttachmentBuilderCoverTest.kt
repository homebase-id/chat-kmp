package id.homebase.chat.services.builder

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
 * Pins the Event cover-photo send path: buildSingle with the cover key produces a
 * single image payload keyed "chat_web0" with a preview thumb and >=1 thumbnail,
 * so the optimistic seed + bubble render get sharp media. Mirrors
 * MessageAttachmentBuilderStickerTest's skia/fake-fs harness.
 */
class MessageAttachmentBuilderCoverTest {

    private fun opaqueJpeg(w: Int = 64, h: Int = 64): ByteArray {
        val info = ImageInfo(
            colorInfo = ColorInfo(ColorType.BGRA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB),
            width = w,
            height = h,
        )
        val bytes = ByteArray(w * h * 4)
        for (i in bytes.indices step 4) {
            bytes[i] = 0
            bytes[i + 1] = (0x80).toByte()
            bytes[i + 2] = (0xFF).toByte()
            bytes[i + 3] = (0xFF).toByte()
        }
        return Image.makeRaster(info, bytes, w * 4).encodeToData(EncodedImageFormat.JPEG)?.bytes
            ?: error("encode failed")
    }

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

    @Test
    fun coverImage_buildsSingleChatWeb0PayloadWithThumbs() = runTest {
        val path = "/tmp/birthday.jpg"
        val bundle = MessageAttachmentBuilder.buildSingle(
            attachment = AttachmentInput(filePath = path, contentType = "image/jpeg"),
            fileOperationsProvider = fakeFsServing(path, opaqueJpeg()),
            payloadKey = "chat_web0",
        )
        val payload = bundle.payloads.single()
        assertEquals("chat_web0", payload.key)
        assertEquals("image/jpeg", payload.contentType)
        assertTrue(payload.previewThumbnail != null, "cover must carry an embedded preview thumb")
        assertTrue(bundle.thumbnails.isNotEmpty(), "cover must generate at least one thumbnail")
        assertTrue(bundle.thumbnails.all { it.key == "chat_web0" }, "thumbnails inherit the cover key")
    }
}
