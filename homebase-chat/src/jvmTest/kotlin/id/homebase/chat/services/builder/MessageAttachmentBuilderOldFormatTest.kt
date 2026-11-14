package id.homebase.chat.services.builder

import id.homebase.api.file.JvmFileOperationsProvider
import id.homebase.api.image.MediaQuality
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Until the switch flips, the shared builder writes what chat's builder always wrote.
class MessageAttachmentBuilderOldFormatTest {

    private val fileOps = JvmFileOperationsProvider()
    private val dir = kotlin.io.path.createTempDirectory("hb-old-format-test").toFile()

    private fun jpeg(w: Int, h: Int): ByteArray {
        val info = ImageInfo(ColorInfo(ColorType.BGRA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB), w, h)
        return Image.makeRaster(info, ByteArray(w * h * 4) { -1 }, w * 4).encodeToData(EncodedImageFormat.JPEG)!!.bytes
    }

    private fun file(name: String, bytes: ByteArray) = File(dir, name).apply { writeBytes(bytes) }.path

    private suspend fun send(input: AttachmentInput, quality: MediaQuality = MediaQuality.STANDARD) =
        MessageAttachmentBuilder.buildSingle(input, fileOps, "chat_web0", quality)

    @Test
    fun anImageKeepsTheTypeItWasGiven() = runTest {
        val sent = send(AttachmentInput(file("a.jpg", jpeg(200, 150)), "image/jpg"), MediaQuality.ORIGINAL)
        assertEquals("image/jpg", sent.payloads.single().contentType)
    }

    @Test
    fun anUntypedFileStaysAFileWithItsName() = runTest {
        val sent = send(AttachmentInput(file("scan", jpeg(200, 150)), "application/octet-stream", displayName = "scan"))
        val payload = sent.payloads.single()
        assertEquals("application/octet-stream", payload.contentType)
        assertEquals("scan", payload.descriptorContent)
        assertTrue(sent.thumbnails.isEmpty())
    }

    @Test
    fun aBlankNameIsWrittenAsBefore() = runTest {
        val memo = send(AttachmentInput(file("rec_8f3a.m4a", ByteArray(8)), "audio/mp4", displayName = "", audioLengthSeconds = 4))
        assertTrue(memo.payloads.single().descriptorContent!!.contains("\"name\":\"file\""), memo.payloads.single().descriptorContent)

        val doc = send(AttachmentInput(file("d.pdf", "%PDF-1.4".encodeToByteArray()), "application/pdf", displayName = ""))
        assertEquals("file", doc.payloads.single().descriptorContent)
    }
}
