package id.homebase.api.image

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Each platform's own EXIF reader gives a real JPEG's camera settings as the common one does. */
class ImageMetadataCameraContractTest {
    @Test
    fun aRealJpegsCameraSettingsAreRead() = runTest {
        val pixels = IntArray(64 * 48) { (0xFF shl 24) or (it % 256 shl 8) or 90 }
        val png = ImageUtils.encodeArgbToPng(ArgbImage(pixels, 64, 48))
        val jpeg = ImageUtils.resizePreserveAspect(png, 64, 48, ImageFormat.JPEG, 90).bytes

        val exif = assertNotNull(readImageMetadata(CameraExifFixture.inJpeg(CameraExifFixture.tiff(littleEndian = false), jpeg)))

        assertEquals(CameraExifFixture.MAKE, exif.cameraMake)
        assertEquals(CameraExifFixture.MODEL, exif.cameraModel)
        assertEquals(1.8, exif.fNumber)
        assertEquals(1.0 / 250, assertNotNull(exif.exposureTimeSeconds), 1e-9)
        assertEquals(50.0, exif.focalLengthMm)
        assertEquals(400, exif.iso)
        assertEquals(UtcOffset(hours = 9), exif.captureUtcOffset)
        assertEquals(LocalDateTime(2026, 1, 15, 15, 0, 0), exif.capturedAt)
        assertEquals(CameraExifFixture.LENS_MAKE, exif.lensMake)
        assertEquals(CameraExifFixture.LENS_MODEL, exif.lensModel)
        assertEquals(-0.7, assertNotNull(exif.exposureBiasEv), 1e-9)
        assertEquals(2, exif.exposureProgram)
        assertEquals(5, exif.meteringMode)
        assertEquals(0x10, exif.flash)
        assertEquals(0, exif.whiteBalance)
        assertEquals(CameraExifFixture.SOFTWARE, exif.software)
        assertEquals(LocalDateTime(2026, 1, 16, 8, 0, 0), exif.modifiedAt)
        assertEquals(123.4, assertNotNull(exif.imageDirectionDegrees), 1e-9)
        assertEquals(5.0, exif.horizontalErrorMeters)
        assertEquals(75, exif.focalLength35mm)
        assertEquals(2.0, exif.digitalZoomRatio)
        assertEquals(3, exif.sceneCaptureType)
        assertEquals(1, exif.customRendered)
        assertEquals(1, exif.colorSpace)
        assertEquals(2.5, assertNotNull(exif.subjectDistanceMeters), 1e-9)
        assertEquals(CameraExifFixture.DESCRIPTION, exif.imageDescription)
        assertEquals(CameraExifFixture.ARTIST, exif.artist)
        assertEquals(CameraExifFixture.COPYRIGHT, exif.copyright)
        assertEquals(CameraExifFixture.COMMENT, exif.userComment)
        assertEquals(CameraExifFixture.BODY_SERIAL, exif.bodySerial)
        assertEquals(CameraExifFixture.LENS_SERIAL, exif.lensSerial)
        assertEquals(Instant.parse("2026-01-15T06:00:30Z"), exif.gpsTime)
        assertEquals(36.0, assertNotNull(exif.speedKmh), 1e-9)

        val plain = assertNotNull(readImageMetadata(CameraExifFixture.inJpeg(CameraExifFixture.tiff(littleEndian = false, plainTimes = true), jpeg)))
        assertEquals(LocalDateTime(2026, 1, 16, 8, 0, 0), plain.capturedAt, "without DateTimeOriginal, the file's DateTime")
        assertEquals(UtcOffset(hours = 9), plain.captureUtcOffset, "without OffsetTimeOriginal, OffsetTime")

        val twoIsos = assertNotNull(readImageMetadata(CameraExifFixture.inJpeg(CameraExifFixture.tiff(littleEndian = false, isoTwice = true), jpeg)))
        assertEquals(400, twoIsos.iso, "an ISO of two values reads as its first")
        assertEquals(400, readImageMetadata(CameraExifFixture.inJpeg(CameraExifFixture.tiff(littleEndian = false, isoThrice = true), jpeg))?.iso)

        val manual = assertNotNull(readImageMetadata(CameraExifFixture.inJpeg(CameraExifFixture.tiff(littleEndian = false, manualLens = true), jpeg)))
        assertNull(manual.fNumber, "a manual lens's 0 is unknown, as the common reader has it")
        assertNull(manual.focalLengthMm)

        val broken = assertNotNull(readImageMetadata(CameraExifFixture.inJpeg(CameraExifFixture.tiff(littleEndian = false, brokenFocalLength = true), jpeg)))
        assertNull(broken.focalLengthMm, "50/0 is no focal length, not infinity")
        assertEquals(1.8, broken.fNumber)
    }
}
