package id.homebase.api.image

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageMetadataTest {

    @Test
    fun readImageMetadata_orientationTaggedJpeg_extractsOrientationAndDimensions() {
        val bytes = ImageTestHelper.loadImage("orientation/Landscape_3.jpg")

        val md = readImageMetadata(bytes)

        assertNotNull(md)
        assertEquals(3, md.orientation)
        assertNotNull(md.pixelWidth)
        assertNotNull(md.pixelHeight)
        assertTrue(md.pixelWidth > 0)
        assertTrue(md.pixelHeight > 0)
    }

    @Test
    fun readImageMetadata_canon5d_extractsCameraAndDate() {
        val bytes = ImageTestHelper.loadImage("canon_5d_srgb.jpg")

        val md = readImageMetadata(bytes)

        assertNotNull(md)
        assertEquals("Canon", md.cameraMake)
        assertEquals("Canon EOS 5D Mark IV", md.cameraModel)
        assertEquals(LocalDateTime(2018, 7, 2, 11, 33, 55), md.capturedAt)
        // No GPS in this fixture
        assertNull(md.latitude)
        assertNull(md.longitude)
        assertNull(md.altitudeMeters)
    }

    @Test
    fun readImageMetadata_garbageBytes_returnsNull() {
        val md = readImageMetadata(ByteArray(64) { it.toByte() })
        assertNull(md)
    }

    @Test
    fun readImageMetadata_emptyBytes_returnsNull() {
        assertNull(readImageMetadata(ByteArray(0)))
    }

    @Test
    fun parseExifLocalDateTime_validString_parses() {
        val parsed = parseExifLocalDateTime("2024:03:15 09:30:45")
        assertEquals(LocalDateTime(2024, 3, 15, 9, 30, 45), parsed)
    }

    @Test
    fun parseExifLocalDateTime_zeroPlaceholder_returnsNull() {
        // Some cameras emit this as a "missing" sentinel.
        assertNull(parseExifLocalDateTime("0000:00:00 00:00:00"))
    }

    @Test
    fun parseExifLocalDateTime_nullOrBlank_returnsNull() {
        assertNull(parseExifLocalDateTime(null))
        assertNull(parseExifLocalDateTime(""))
        assertNull(parseExifLocalDateTime("   "))
    }

    @Test
    fun parseExifLocalDateTime_garbage_returnsNull() {
        assertNull(parseExifLocalDateTime("not a date"))
        assertNull(parseExifLocalDateTime("2024-03-15"))
    }

    @Test
    fun parseExifUtcOffset_validOffset_parses() {
        assertEquals(60 * 60, parseExifUtcOffset("+01:00")!!.totalSeconds)
        assertEquals(-5 * 60 * 60, parseExifUtcOffset("-05:00")!!.totalSeconds)
    }

    @Test
    fun parseExifUtcOffset_garbage_returnsNull() {
        assertNull(parseExifUtcOffset("garbage"))
        assertNull(parseExifUtcOffset(null))
    }
}
