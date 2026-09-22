package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JpegExifOrientationTest {

    @Test
    fun swapsDimensions_onlyForQuarterTurns() {
        for (orientation in 1..4) assertFalse(jpegOrientationSwapsDimensions(orientation))
        for (orientation in 5..8) assertTrue(jpegOrientationSwapsDimensions(orientation))
    }

    @Test
    fun readJpegExifOrientation_nonJpeg_returnsNull() {
        assertNull(readJpegExifOrientation(ByteArray(0)))
        assertNull(readJpegExifOrientation(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertNull(readJpegExifOrientation(ByteArray(256) { it.toByte() }))
    }

    @Test
    fun readJpegExifOrientation_jpegWithoutExif_returnsNull() {
        // SOI + a JFIF APP0 segment + SOS: valid JPEG framing, no EXIF.
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xDA.toByte(),
        )
        assertNull(readJpegExifOrientation(bytes))
    }

    @Test
    fun readJpegExifOrientation_truncatedExifSegment_returnsNull() {
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(), 0x00, 0x20,
            0x45, 0x78, 0x69, 0x66, 0x00, 0x00,
            0x49, 0x49, 0x2A, 0x00,
        )
        assertNull(readJpegExifOrientation(bytes))
    }

    @Test
    fun readJpegExifOrientation_littleAndBigEndianExif_readsTag() {
        assertEquals(6, readJpegExifOrientation(exifJpeg(orientation = 6, littleEndian = true)))
        assertEquals(6, readJpegExifOrientation(exifJpeg(orientation = 6, littleEndian = false)))
        assertEquals(1, readJpegExifOrientation(exifJpeg(orientation = 1, littleEndian = true)))
    }

    @Test
    fun readJpegExifOrientation_app1LongerThanSuppliedBytes_stillReadsTag() {
        // The web decoder only peeks the head of the file; a phone's APP1 runs
        // past that because of its embedded thumbnail.
        val full = exifJpeg(orientation = 8, littleEndian = true, declaredExtraBytes = 40_000)
        assertEquals(8, readJpegExifOrientation(full))
    }

    /** Minimal SOI + APP1/EXIF segment carrying a single orientation entry. */
    private fun exifJpeg(
        orientation: Int,
        littleEndian: Boolean,
        declaredExtraBytes: Int = 0,
    ): ByteArray {
        fun u16(value: Int): List<Byte> =
            if (littleEndian) listOf((value and 0xFF).toByte(), (value shr 8).toByte())
            else listOf((value shr 8).toByte(), (value and 0xFF).toByte())

        fun u32(value: Int): List<Byte> =
            if (littleEndian) {
                listOf(
                    (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
                    ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte(),
                )
            } else {
                listOf(
                    ((value shr 24) and 0xFF).toByte(), ((value shr 16) and 0xFF).toByte(),
                    ((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte(),
                )
            }

        val tiff = buildList {
            val marker = if (littleEndian) 0x49.toByte() else 0x4D.toByte()
            add(marker); add(marker)
            addAll(u16(42))
            addAll(u32(8))
            addAll(u16(1))
            addAll(u16(0x0112))
            addAll(u16(3))
            addAll(u32(1))
            addAll(u16(orientation))
            addAll(u16(0))
        }
        val payload = listOf<Byte>(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) + tiff
        val length = payload.size + 2 + declaredExtraBytes
        return (
            listOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte()) +
                listOf((length shr 8).toByte(), (length and 0xFF).toByte()) +
                payload
            ).toByteArray()
    }
}
