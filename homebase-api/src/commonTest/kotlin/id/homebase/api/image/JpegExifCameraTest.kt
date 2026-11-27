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
    fun aManualLensesZerosAreUnknown() {
        val exif = assertNotNull(readJpegExifCamera(CameraExifFixture.inJpeg(CameraExifFixture.tiff(littleEndian = true, manualLens = true))))

        assertNull(exif.fNumber)
        assertNull(exif.focalLengthMm)
        assertEquals(1.0 / 250, exif.exposureTimeSeconds)
    }

    @Test
    fun aCorruptOffsetOrCountReadsAsMissingNotAThrow() {
        val tiff = CameraExifFixture.tiff(littleEndian = true)
        // IFD0's offset, FF FF FF FF: clamped to Int.MAX_VALUE, it once wrapped past the bounds check.
        val noIfd = tiff.copyOf().also { for (i in 4..7) it[i] = 0xFF.toByte() }
        assertNull(readJpegExifCamera(CameraExifFixture.inJpeg(noIfd)))
        assertNull(readJpegExifOrientation(CameraExifFixture.inJpeg(noIfd)))

        // Make's count, 0x80000000, the first IFD0 entry's at 10 + 4: the rest still reads.
        val hugeMake = tiff.copyOf().also { it[14] = 0; it[15] = 0; it[16] = 0; it[17] = 0x80.toByte() }
        val exif = assertNotNull(readJpegExifCamera(CameraExifFixture.inJpeg(hugeMake)))
        assertNull(exif.cameraMake)
        assertEquals(CameraExifFixture.MODEL, exif.cameraModel)
        assertEquals(6, readJpegExifOrientation(CameraExifFixture.inJpeg(hugeMake)))
    }

    @Test
    fun notAJpegWithExifIsNull() {
        assertNull(readJpegExifCamera(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
        assertNull(readJpegExifCamera("not an image".encodeToByteArray()))
    }
}
