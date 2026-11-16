package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

class JpegMetadataTest {
    @Test
    fun anEmbeddedExifIsReadBackFromTheJpeg() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0, 4, 1, 2, 0xFF.toByte(), 0xD9.toByte())
        val tiff = byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0)
        val withExif = JpegMetadata.embed(jpeg, tiff)
        assertContentEquals(tiff, assertNotNull(ImageMetadataScrubber.readBlocks(withExif)?.exif))
        // Everything after SOI follows unchanged.
        assertContentEquals(jpeg.copyOfRange(2, jpeg.size), withExif.copyOfRange(withExif.size - (jpeg.size - 2), withExif.size))
    }
}
