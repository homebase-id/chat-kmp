package id.homebase.api.image

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
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
            assertEquals(UtcOffset(hours = 9), exif.captureUtcOffset)
            assertEquals(LocalDateTime(2026, 1, 15, 15, 0, 0), exif.capturedAt)
            assertEquals(CameraExifFixture.LENS_MAKE, exif.lensMake)
            assertEquals(CameraExifFixture.LENS_MODEL, exif.lensModel)
            assertEquals(-0.7, exif.exposureBiasEv)
            assertEquals(2, exif.exposureProgram)
            assertEquals(5, exif.meteringMode)
            assertEquals(0x10, exif.flash)
            assertEquals(0, exif.whiteBalance)
            assertEquals(CameraExifFixture.SOFTWARE, exif.software)
            assertEquals(LocalDateTime(2026, 1, 16, 8, 0, 0), exif.modifiedAt)
            assertEquals(123.4, exif.imageDirectionDegrees)
            assertEquals(5.0, exif.horizontalErrorMeters)
            assertNull(exif.latitude, "web reads no position: its uploads would record it")
        }
    }

    @Test
    fun anIsoOfTwoValuesIsItsFirstAndAnIfdTypedPointerIsFollowed() {
        assertEquals(400, readJpegExifCamera(CameraExifFixture.inJpeg(CameraExifFixture.tiff(littleEndian = true, isoTwice = true)))?.iso)
        assertEquals(400, readJpegExifCamera(CameraExifFixture.inJpeg(CameraExifFixture.tiff(littleEndian = false, isoThrice = true)))?.iso, "past the inline field, at its offset")
        assertEquals(1.8, readJpegExifCamera(CameraExifFixture.inJpeg(CameraExifFixture.tiff(littleEndian = false, exifPointerType = 13)))?.fNumber)
    }

    @Test
    fun withoutTheOriginalsTheFileTimeAndPlainOffsetAreRead() {
        val exif = assertNotNull(readJpegExifCamera(CameraExifFixture.inJpeg(CameraExifFixture.tiff(littleEndian = true, plainTimes = true))))

        assertEquals(LocalDateTime(2026, 1, 16, 8, 0, 0), exif.capturedAt)
        assertEquals(UtcOffset(hours = 9), exif.captureUtcOffset)
    }

    @Test
    fun aRationalOfSeveralValuesIsUnknown() {
        // FNumber's count, the Exif IFD's second entry.
        val tiff = CameraExifFixture.tiff(littleEndian = true).also { it[CameraExifFixture.exifCountAt(1)] = 2 }

        assertNull(assertNotNull(readJpegExifCamera(CameraExifFixture.inJpeg(tiff))).fNumber)
    }

    @Test
    fun aTagOfNoValuesIsUnknown() {
        // ISO's count, the Exif IFD's fourth entry.
        val tiff = CameraExifFixture.tiff(littleEndian = true).also { it[CameraExifFixture.exifCountAt(3)] = 0 }

        assertNull(assertNotNull(readJpegExifCamera(CameraExifFixture.inJpeg(tiff))).iso)
    }

    @Test
    fun unknownCodesAndOutOfRangeValuesAreNull() {
        // Metering mode 0 "unknown"; the program's count 0.
        val tiff = CameraExifFixture.tiff(littleEndian = true).also {
            it[CameraExifFixture.exifCountAt(7) + 4] = 0
            it[CameraExifFixture.exifCountAt(2)] = 0
        }

        val exif = assertNotNull(readJpegExifCamera(CameraExifFixture.inJpeg(tiff)))

        assertNull(exif.meteringMode)
        assertNull(exif.exposureProgram)
        assertEquals(0x10, exif.flash)
    }

    @Test
    fun theOrientationReaderReadsTheSameBlock() {
        assertEquals(6, readJpegExifOrientation(CameraExifFixture.inJpeg(CameraExifFixture.tiff(littleEndian = false))))
    }

    @Test
    fun valuesPastTheBytesGivenAreLeftOutAndTheRestRead() {
        val tiff = CameraExifFixture.tiff(littleEndian = true)
        // Cut inside the values: the names and rationals lie past the end, the IFDs and ISO don't.
        val head = CameraExifFixture.inJpeg(tiff).let { it.copyOf(it.size - 2 - (tiff.size - CameraExifFixture.MAKE_AT - 4)) }

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
        // IFD0's offset, FF FF FF FF: clamped to Int.MAX_VALUE, past the end.
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
    fun aBrokenRationalIsUnknown() {
        assertNull(assertNotNull(readJpegExifCamera(CameraExifFixture.inJpeg(CameraExifFixture.tiff(littleEndian = true, brokenFocalLength = true)))).focalLengthMm)
    }

    @Test
    fun aNameEndsAtItsFirstNul() {
        val tiff = CameraExifFixture.tiff(littleEndian = true).also { it[CameraExifFixture.MAKE_AT + 2] = 0 }

        assertEquals("Ca", assertNotNull(readJpegExifCamera(CameraExifFixture.inJpeg(tiff))).cameraMake)
    }

    @Test
    fun notAJpegWithExifIsNull() {
        assertNull(readJpegExifCamera(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
        assertNull(readJpegExifCamera("not an image".encodeToByteArray()))
    }
}
