package id.homebase.chat.services.builder

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageUtils
import id.homebase.api.image.ArgbImage
import id.homebase.api.image.ImageFormat
import id.homebase.api.image.ImageSize
import id.homebase.api.image.RenderedImage
import id.homebase.api.image.MediaQuality
import id.homebase.upload.ThumbnailResult
import id.homebase.upload.UploadImages
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Standard and HD: a photo's payload becomes the final image, and anything that would lose
 * information, or gain nothing, keeps its file.
 */
class FinalImagePayloadTest {

    private fun fixture(name: String): ByteArray =
        this::class.java.getResourceAsStream("/test_images/$name")?.readBytes()
            ?: error("Test image not found: $name")

    private fun input(contentType: String, forceSticker: Boolean = false) =
        AttachmentInput(
            filePath = "/tmp/source-does-not-need-to-exist",
            contentType = contentType,
            forceSticker = forceSticker,
        )

    private fun noise(width: Int, height: Int): ByteArray {
        val random = Random(1)
        return ImageUtils.encodeArgbToPng(ArgbImage(IntArray(width * height) { random.nextInt() or (0xFF shl 24) }, width, height))
    }

    private fun gradientPng(width: Int, height: Int): ByteArray {
        val random = Random(2)
        val pixels = IntArray(width * height) { (0xFF shl 24) or ((it % width) * 255 / width shl 16) or random.nextInt(8) }
        return ImageUtils.encodeArgbToPng(ArgbImage(pixels, width, height))
    }

    private class Serving(val bytes: ByteArray) : TempWritingFileOps() {
        override suspend fun readFileBytes(path: String): ByteArray = bytes
    }

    private suspend fun send(
        attachment: AttachmentInput,
        bytes: ByteArray,
        quality: MediaQuality = MediaQuality.STANDARD,
    ): String? {
        val ops = Serving(bytes)
        val thumbs = UploadImages.generate(attachment.filePath, "chat_web0", ops, quality, attachment.forceSticker)
        return UploadImages.finalImagePath(thumbs, ops)
    }

    @Test
    fun aLargePhotoBecomesA2048pxWebpOnStandard() = runTest {
        val attachment = input("image/jpeg")

        val result = assertNotNull(send(attachment, fixture("5760_x_4320.jpg")))

        val written = File(result).readBytes()
        assertEquals("RIFF", written.copyOf(4).decodeToString())
        val size = ImageUtils.getNaturalSize(written)
        assertEquals(2048, max(size.pixelWidth, size.pixelHeight))
    }

    @Test
    fun aLargePhotoStopsAt4096OnHd() = runTest {
        val result = assertNotNull(send(input("image/jpeg"), fixture("5760_x_4320.jpg"), MediaQuality.HD))

        val size = ImageUtils.getNaturalSize(File(result).readBytes())
        assertEquals(4096, max(size.pixelWidth, size.pixelHeight))
    }

    @Test
    fun originalKeepsItsFileWhateverItsSize() = runTest {
        val attachment = input("image/jpeg")

        assertNull(send(attachment, fixture("5760_x_4320.jpg"), MediaQuality.ORIGINAL))
    }

    @Test
    fun aSmallCompressedJpegThatFitsKeepsItsFile() = runTest {
        // Already compressed: a WebP at q70 can't save a quarter on a tiny, clean JPEG.
        val attachment = input("image/jpeg")
        val bytes = ImageUtils.resizePreserveAspect(noise(400, 300), 400, 400, ImageFormat.JPEG, 40).bytes

        assertNull(send(attachment, bytes))
    }

    @Test
    fun aSourceThatFitsIsReEncodedIfThatSavesAQuarter() = runTest {
        // A PNG photo: lossless, so a WebP saves far more than a quarter.
        val attachment = input("image/png")

        val result = assertNotNull(send(attachment, gradientPng(600, 400)))

    }

    @Test
    fun animatedAndVectorAndStickerSourcesKeepTheirFile() = runTest {
        val gif = input("image/gif")
        val svg = input("image/svg+xml")
        val sticker = input("image/png", forceSticker = true)

        assertNull(send(gif, ImageTestGif))
        assertNull(send(svg, """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"/>""".encodeToByteArray()))
        assertNull(send(sticker, gradientPng(600, 400)))
    }

    @Test
    fun animatedWebpKeepsItsFileButAStaticOneOfTheSameSizeDoesNot() = runTest {
        val decoder = java.util.Base64.getDecoder()
        val animated = decoder.decode(ANIMATED_WEBP_1700x100)
        val still = decoder.decode(STATIC_WEBP_1700x100)
        val attachment = input("image/webp")
        assertEquals(1700, ImageUtils.getNaturalSize(animated).pixelWidth)

        assertNull(send(attachment, animated))

        // The animation check is what keeps the animated one out of the still encoder.
        val thumbs = UploadImages.generate("/unused", "chat_web0", Serving(still), MediaQuality.HD)
        assertEquals(1700, thumbs.finalImage?.width)
    }

    @Test
    fun anUndecodableSourceSendsTheFileWithNoThumbnails() = runTest {
        val attachment = input("image/jpeg")
        val garbage = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val ops = Serving(garbage)

        val thumbs = UploadImages.generate(attachment.filePath, "chat_web0", ops, MediaQuality.STANDARD)

        assertTrue(thumbs.thumbnails.isEmpty())
        assertNull(UploadImages.finalImagePath(thumbs, ops))
    }

    @Test
    fun aFullDiskSendsTheFileInsteadOfFailingTheSend() = runTest {
        val attachment = input("image/jpeg")
        val ops = object : TempWritingFileOps() {
            override suspend fun readFileBytes(path: String): ByteArray = noise(3000, 2000)

            override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String =
                throw IllegalStateException("No space left on device")
        }
        val thumbs = UploadImages.generate(attachment.filePath, "chat_web0", ops, MediaQuality.STANDARD)

        assertNull(UploadImages.finalImagePath(thumbs, ops))
    }

    private companion object {
        val ImageTestGif = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 0, 1, 0, 0, 0, 0, 0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0, 0x3B)
        // Solid-colour lossless WebPs, so a 1700px canvas stays tiny.
        const val ANIMATED_WEBP_1700x100 =
            "UklGRoQAAABXRUJQVlA4WAoAAAACAAAAowYAYwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAKMGAGMAAGQAAAJWUDhM" +
                "DwAAAC+jxhgABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAKMGAGMAAGQAAABWUDhMDwAAAC+jxhgABxDR//4HIqL/AQA="
        const val STATIC_WEBP_1700x100 =
            "UklGRiwAAABXRUJQVlA4TB8AAAAvo8YYAAcQ/Y/+BwykbTP/9rc/Ef3P8J///Oc//3sDAA=="
    }

    @Test
    fun anUnknownSourceSizeNeverKeepsTheFileOverItsFinalImage() = runTest {
        val attachment = input("image/png")
        val finalImage = RenderedImage(ByteArray(10), 600, 400, 70, ImageFormat.WEBP)
        val thumbs = ThumbnailResult(null, emptyList(), "image/png", sourceByteCount = null, finalImage, ImageSize(600, 400))

        assertNotNull(UploadImages.finalImagePath(thumbs, Serving(ByteArray(0))))
    }
}

private open class ExplodingFileOps : FileOperationsProvider {
    override fun openFileInput(path: String): InputProvider = throw NotImplementedError()
    override suspend fun readFileBytes(path: String): ByteArray = throw NotImplementedError()
    override fun deleteTempFile(path: String): Boolean = false
    override fun getCacheDirectory(): String = "/tmp"
    override fun getFileSize(path: String): Long = 0L
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String =
        error("must not write a temp file")
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String =
        throw NotImplementedError()
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) = throw NotImplementedError()
}

private open class TempWritingFileOps : ExplodingFileOps() {
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String {
        val file = File.createTempFile(prefix, suffix)
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file.absolutePath
    }

}
