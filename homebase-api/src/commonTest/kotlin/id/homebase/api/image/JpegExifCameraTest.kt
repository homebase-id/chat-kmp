package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JpegExifCameraTest {
    @Test
    fun aCamerasSettingsAreReadInEitherByteOrder() {
        for (littleEndian in listOf(true, false)) {
            val exif = assertNotNull(readJpegExifCamera(CameraExifFixture.inJpeg(CameraExifFixture.tiff(littleEndian))), "littleEndian=$littleEndian")

            assertEquals(CameraExifFixture.MAKE, exif.cameraMake)
            assertEquals(CameraExifFixture.MODEL, exif.cameraModel)
            assertEquals(6, exif.orientation)
            assertEquals(1.8, exif.fNumber)
            assertEquals(1.0 / 250, exif.exposureTimeSeconds)
            assertEquals(50.0, exif.focalLengthMm)
            assertEquals(400, exif.iso)
        }
    }

    @Test
    fun theOrientationReaderReadsTheSameBlock() {
        assertEquals(6, readJpegExifOrientation(CameraExifFixture.inJpeg(CameraExifFixture.tiff(littleEndian = false))))
    }

    @Test
    fun valuesPastTheBytesGivenAreLeftOutAndTheRestRead() {
        val tiff = CameraExifFixture.tiff(littleEndian = true)
        // Cut inside the values: the names and rationals lie past the end, the IFDs and ISO don't.
        val head = CameraExifFixture.inJpeg(tiff).let { it.copyOf(it.size - 2 - (tiff.size - 120)) }

        val exif = assertNotNull(readJpegExifCamera(head))

        assertEquals(400, exif.iso)
        assertEquals(6, exif.orientation)
        assertNull(exif.cameraModel)
        assertNull(exif.focalLengthMm)
    }

    @Test
    fun notAJpegWithExifIsNull() {
        assertNull(readJpegExifCamera(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
        assertNull(readJpegExifCamera("not an image".encodeToByteArray()))
    }
}
