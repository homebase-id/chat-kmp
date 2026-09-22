package id.homebase.chat.services.builder

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageUtils
import id.homebase.api.image.standardPrimaryImage
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Standard-quality contract from #1369: a photo's payload becomes a 1600px WebP, and anything
 * that would lose information — or gain nothing — is passed through untouched.
 */
class StandardQualityImageTest {

    private fun fixture(name: String): ByteArray =
        this::class.java.getResourceAsStream("/test_images/$name")?.readBytes()
            ?: error("Test image not found: $name")

    private fun input(contentType: String, forceSticker: Boolean = false) =
        AttachmentInput(
            filePath = "/tmp/source-does-not-need-to-exist",
            contentType = contentType,
            forceSticker = forceSticker,
        )

    @Test
    fun oversizePhoto_isReEncodedToA1600pxWebp() = runTest {
        val source = fixture("5760_x_4320.jpg")
        val fileOps = TempWritingFileOps()

        val result = standardQualityImage(input("image/jpeg"), source, "chat_web0", fileOps)

        assertEquals("image/webp", result.contentType)
        assertNotEquals(input("image/jpeg").filePath, result.filePath)

        val encoded = File(result.filePath).readBytes()
        // RIFF magic — proves the payload really is WebP and not the original relabelled, which is
        // what createImageThumbnail's pass-through fast path would have handed back.
        assertTrue(
            encoded[0] == 0x52.toByte() && encoded[1] == 0x49.toByte() &&
                encoded[2] == 0x46.toByte() && encoded[3] == 0x46.toByte(),
            "payload is not a WebP: ${encoded.take(4)}",
        )

        val size = ImageUtils.getNaturalSize(encoded)
        assertEquals(1600, max(size.pixelWidth, size.pixelHeight))
        assertTrue(
            encoded.size <= standardPrimaryImage.maxBytes,
            "payload ${encoded.size} B exceeded the ${standardPrimaryImage.maxBytes} B cap",
        )
    }

    @Test
    fun photoThatAlreadyFits_isNotReEncoded() = runTest {
        val source = fixture("little_gradient_whitespace.jpg")
        val attachment = input("image/jpeg")

        val result = standardQualityImage(attachment, source, "chat_web0", ExplodingFileOps())

        // Same instance: no temp file written, no generational loss, content type preserved.
        assertSame(attachment, result)
    }

    @Test
    fun animatedAndVectorAndStickerSources_arePassedThrough() = runTest {
        val gif = input("image/gif")
        val svg = input("image/svg+xml")
        val sticker = input("image/png", forceSticker = true)
        val bytes = fixture("5760_x_4320.jpg")

        assertSame(gif, standardQualityImage(gif, bytes, "chat_web0", ExplodingFileOps()))
        assertSame(svg, standardQualityImage(svg, bytes, "chat_web0", ExplodingFileOps()))
        assertSame(sticker, standardQualityImage(sticker, bytes, "chat_web0", ExplodingFileOps()))
    }

    @Test
    fun animatedWebpIsPassedThroughButAStaticOneOfTheSameSizeIsNot() = runTest {
        val decoder = java.util.Base64.getDecoder()
        val animated = decoder.decode(ANIMATED_WEBP_1700x100)
        val still = decoder.decode(STATIC_WEBP_1700x100)
        // Oversize and decodable, so only the animation check keeps it out of the still encoder.
        assertEquals(1700, ImageUtils.getNaturalSize(animated).pixelWidth)

        val attachment = input("image/webp")
        assertSame(attachment, standardQualityImage(attachment, animated, "chat_web0", TempWritingFileOps()))

        val reEncoded = standardQualityImage(input("image/webp"), still, "chat_web0", TempWritingFileOps())
        assertEquals(1600, ImageUtils.getNaturalSize(File(reEncoded.filePath).readBytes()).pixelWidth)
    }

    @Test
    fun anUndecodableSourceSendsTheOriginalRatherThanBlockingTheSend() = runTest {
        val attachment = input("image/jpeg")
        val garbage = ByteArray(64) { 0x7F }

        assertSame(attachment, standardQualityImage(attachment, garbage, "chat_web0", ExplodingFileOps()))
    }

    private companion object {
        // Solid-colour lossless WebPs, so a 1700px canvas stays tiny.
        const val ANIMATED_WEBP_1700x100 =
            "UklGRoQAAABXRUJQVlA4WAoAAAACAAAAowYAYwAAQU5JTQYAAAAAAAAAAABBTk1GKAAAAAAAAAAAAKMGAGMAAGQAAAJWUDhM" +
                "DwAAAC+jxhgABxD9j/4HIqL/AQBBTk1GKAAAAAAAAAAAAKMGAGMAAGQAAABWUDhMDwAAAC+jxhgABxDR//4HIqL/AQA="
        const val STATIC_WEBP_1700x100 =
            "UklGRiwAAABXRUJQVlA4TB8AAAAvo8YYAAcQ/Y/+BwykbTP/9rc/Ef3P8J///Oc//3sDAA=="
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

private class TempWritingFileOps : ExplodingFileOps() {
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String {
        val file = File.createTempFile(prefix, suffix)
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file.absolutePath
    }
}
