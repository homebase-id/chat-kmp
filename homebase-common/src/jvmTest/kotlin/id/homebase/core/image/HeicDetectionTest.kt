package id.homebase.core.image

import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import okio.Buffer
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeicDetectionTest {

    private fun sourceResult(bytes: ByteArray, mimeType: String? = null): SourceFetchResult =
        SourceFetchResult(ImageSource(Buffer().write(bytes), FileSystem.SYSTEM), mimeType, DataSource.MEMORY)

    @Test
    fun `heic and heif mimeTypes are HEIC`() {
        assertTrue(isHeicSource(sourceResult(ByteArray(16), mimeType = "image/heic")))
        assertTrue(isHeicSource(sourceResult(ByteArray(16), mimeType = "image/heif")))
    }

    @Test
    fun `heic and mif1 ftyp headers are HEIC`() {
        assertTrue(isHeicSource(sourceResult(ftypHeader("heic"))))
        assertTrue(isHeicSource(sourceResult(ftypHeader("mif1"))))
    }

    @Test
    fun `jpeg mimeType is not HEIC`() {
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(8)
        assertFalse(isHeicSource(sourceResult(jpegHeader, mimeType = "image/jpeg")))
    }

    @Test
    fun `png, empty and mp4 bytes are not HEIC`() {
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(4)
        assertFalse(isHeicSource(sourceResult(pngHeader)))
        assertFalse(isHeicSource(sourceResult(ByteArray(0))))
        assertFalse(isHeicSource(sourceResult(ftypHeader("mp41"))))
    }
}

internal fun ftypHeader(brand: String): ByteArray =
    byteArrayOf(0, 0, 0, 12) + "ftyp".encodeToByteArray() + brand.encodeToByteArray()
