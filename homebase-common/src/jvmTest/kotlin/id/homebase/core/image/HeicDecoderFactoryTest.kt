package id.homebase.core.image

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.decode.DataSource
import okio.Buffer
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeicDecoderFactoryTest {

    private val factory = HeicDecoder.Factory()
    private val options = Options(PlatformContext.INSTANCE)
    private val imageLoader = ImageLoader.Builder(PlatformContext.INSTANCE).build()

    private fun sourceResult(bytes: ByteArray, mimeType: String? = null): SourceFetchResult {
        val buffer = Buffer().write(bytes)
        val source = ImageSource(buffer, FileSystem.SYSTEM)
        return SourceFetchResult(source, mimeType, DataSource.MEMORY)
    }

    // --- HEIC by mimeType ---

    @Test
    fun `factory returns decoder for image-heic mimeType`() {
        val result = sourceResult(ByteArray(16), mimeType = "image/heic")
        assertNotNull(factory.create(result, options, imageLoader))
    }

    @Test
    fun `factory returns decoder for image-heif mimeType`() {
        val result = sourceResult(ByteArray(16), mimeType = "image/heif")
        assertNotNull(factory.create(result, options, imageLoader))
    }

    // --- HEIC by magic bytes ---

    @Test
    fun `factory returns decoder for heic ftyp header`() {
        val header = makeHeicHeader("heic")
        val result = sourceResult(header, mimeType = null)
        assertNotNull(factory.create(result, options, imageLoader))
    }

    @Test
    fun `factory returns decoder for mif1 ftyp header`() {
        val header = makeHeicHeader("mif1")
        val result = sourceResult(header, mimeType = null)
        assertNotNull(factory.create(result, options, imageLoader))
    }

    // --- Non-HEIC ---

    @Test
    fun `factory returns null for image-jpeg mimeType`() {
        val jpegHeader = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val result = sourceResult(jpegHeader, mimeType = "image/jpeg")
        assertNull(factory.create(result, options, imageLoader))
    }

    @Test
    fun `factory returns null for png bytes with no mimeType`() {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x00,
        )
        val result = sourceResult(pngHeader, mimeType = null)
        assertNull(factory.create(result, options, imageLoader))
    }

    @Test
    fun `factory returns null for empty bytes with no mimeType`() {
        val result = sourceResult(ByteArray(0), mimeType = null)
        assertNull(factory.create(result, options, imageLoader))
    }

    @Test
    fun `factory returns null for mp4 ftyp header`() {
        val header = makeHeicHeader("mp41")
        val result = sourceResult(header, mimeType = null)
        assertNull(factory.create(result, options, imageLoader))
    }

    // --- isHeicSource shared function ---

    @Test
    fun `isHeicSource returns true for heic mimeType`() {
        val result = sourceResult(ByteArray(16), mimeType = "image/heic")
        assertTrue(isHeicSource(result))
    }

    @Test
    fun `isHeicSource returns true for heic ftyp bytes with null mimeType`() {
        val result = sourceResult(makeHeicHeader("heic"), mimeType = null)
        assertTrue(isHeicSource(result))
    }

    @Test
    fun `isHeicSource returns false for jpeg mimeType`() {
        val result = sourceResult(ByteArray(16), mimeType = "image/jpeg")
        assertFalse(isHeicSource(result))
    }

    private fun makeHeicHeader(brand: String): ByteArray {
        require(brand.length == 4)
        val bytes = ByteArray(12)
        bytes[0] = 0; bytes[1] = 0; bytes[2] = 0; bytes[3] = 12
        bytes[4] = 0x66; bytes[5] = 0x74; bytes[6] = 0x79; bytes[7] = 0x70
        val b = brand.toByteArray(Charsets.US_ASCII)
        bytes[8] = b[0]; bytes[9] = b[1]; bytes[10] = b[2]; bytes[11] = b[3]
        return bytes
    }
}
