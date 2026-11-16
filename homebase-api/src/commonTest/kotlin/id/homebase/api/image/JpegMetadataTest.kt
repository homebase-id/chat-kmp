package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JpegMetadataTest {
    private val tiff = byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0)
    private val exifSize = 2 + 2 + 6 + tiff.size

    @Test
    fun anEmbeddedExifIsReadBackFromTheJpeg() {
        val jpeg = bytes(0xFF, 0xD8) + bytes(0xFF, 0xDB, 0, 4, 1, 2) + bytes(0xFF, 0xD9)
        val withExif = ImageMetadataScrubber.withExif(jpeg, tiff)
        assertContentEquals(tiff, assertNotNull(ImageMetadataScrubber.readBlocks(withExif)?.exif))
        // Everything after SOI follows unchanged.
        assertContentEquals(jpeg.copyOfRange(2, jpeg.size), withExif.copyOfRange(withExif.size - (jpeg.size - 2), withExif.size))
    }

    @Test
    fun theExifGoesAfterJfifAndTheMpfIndexCountsItInThePrimaryImage() {
        val jfif = bytes(0xFF, 0xE0, 0, 4, 0x4A, 0x46)
        val mpf = mpfSegment(primarySize = 1000, gainmapOffset = 900)
        val jpeg = bytes(0xFF, 0xD8) + jfif + mpf + bytes(0xFF, 0xD9)

        val out = ImageMetadataScrubber.withExif(jpeg, tiff)

        assertContentEquals(jfif, out.copyOfRange(2, 2 + jfif.size), "JFIF stays first")
        assertEquals(0xE1, out[2 + jfif.size + 1].toInt() and 0xFF, "the Exif APP1 follows it")
        val movedMpf = out.copyOfRange(2 + jfif.size + exifSize, 2 + jfif.size + exifSize + mpf.size)
        val entries = 4 + 4 + 26 // marker and length, "MPF\0", the TIFF header and IFD before the entry list
        assertEquals(1000 + exifSize, u32(movedMpf, entries + 4), "the primary image grew by the Exif segment")
        assertEquals(900, u32(movedMpf, entries + 16 + 8), "the gain map's offset counts from the index, which moved with it")
    }

    // An MPF APP2 with two entries, big-endian: the primary image, then the gain map.
    private fun mpfSegment(primarySize: Int, gainmapOffset: Int): ByteArray {
        val tiffHeader = bytes(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8)
        val ifd = bytes(0, 1) + bytes(0xB0, 0x02, 0, 7) + be32(32) + be32(26) + be32(0)
        val list = be32(0x030000) + be32(primarySize) + be32(0) + bytes(0, 0, 0, 0) +
            be32(0) + be32(500) + be32(gainmapOffset) + bytes(0, 0, 0, 0)
        val payload = "MPF".encodeToByteArray() + bytes(0) + tiffHeader + ifd + list
        val length = payload.size + 2
        return bytes(0xFF, 0xE2, length shr 8, length and 0xFF) + payload
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun be32(v: Int) = bytes(v ushr 24 and 0xFF, v ushr 16 and 0xFF, v ushr 8 and 0xFF, v and 0xFF)

    private fun u32(b: ByteArray, at: Int) = (0 until 4).fold(0) { acc, k -> (acc shl 8) or (b[at + k].toInt() and 0xFF) }
}
