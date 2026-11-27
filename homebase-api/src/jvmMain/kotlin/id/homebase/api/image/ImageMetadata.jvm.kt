package id.homebase.api.image

import co.touchlab.kermit.Logger
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.jpeg.JpegDirectory
import com.drew.metadata.png.PngDirectory
import java.io.ByteArrayInputStream

actual fun readImageMetadata(srcBytes: ByteArray): ImageMetadata? {
    val metadata: Metadata = try {
        ByteArrayInputStream(srcBytes).use { ImageMetadataReader.readMetadata(it) }
    } catch (e: Throwable) {
        Logger.w(throwable = e, tag = "readImageMetadata") { "JVM metadata read failed" }
        return null
    }

    val sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
    val ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
    val gps = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)

    val capturedAt = parseExifLocalDateTime(
        sub?.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
            ?: ifd0?.getString(ExifIFD0Directory.TAG_DATETIME)
    )
    val offset = parseExifUtcOffset(
        sub?.getString(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL)
            ?: sub?.getString(ExifSubIFDDirectory.TAG_TIME_ZONE)
    )

    val geo = gps?.geoLocation?.takeUnless { it.isZero }
    val altitude = gps?.let { dir ->
        if (!dir.containsTag(GpsDirectory.TAG_ALTITUDE)) return@let null
        val value = dir.getRationalOrNull(GpsDirectory.TAG_ALTITUDE)?.toDouble() ?: return@let null
        val belowSea = dir.getInteger(GpsDirectory.TAG_ALTITUDE_REF) == 1
        if (belowSea) -value else value
    }

    val orientation = ifd0?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)?.takeIf { it in 1..8 }

    val pixelWidth = firstNonNullInt(
        sub?.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH),
        metadata.getFirstDirectoryOfType(JpegDirectory::class.java)
            ?.getInteger(JpegDirectory.TAG_IMAGE_WIDTH),
        metadata.getFirstDirectoryOfType(PngDirectory::class.java)
            ?.getInteger(PngDirectory.TAG_IMAGE_WIDTH),
    )
    val pixelHeight = firstNonNullInt(
        sub?.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT),
        metadata.getFirstDirectoryOfType(JpegDirectory::class.java)
            ?.getInteger(JpegDirectory.TAG_IMAGE_HEIGHT),
        metadata.getFirstDirectoryOfType(PngDirectory::class.java)
            ?.getInteger(PngDirectory.TAG_IMAGE_HEIGHT),
    )

    return ImageMetadata(
        capturedAt = capturedAt,
        captureUtcOffset = offset,
        latitude = geo?.latitude,
        longitude = geo?.longitude,
        altitudeMeters = altitude,
        orientation = orientation,
        pixelWidth = pixelWidth,
        pixelHeight = pixelHeight,
        cameraMake = ifd0?.getString(ExifIFD0Directory.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() },
        cameraModel = ifd0?.getString(ExifIFD0Directory.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
        fNumber = sub?.positiveDouble(ExifSubIFDDirectory.TAG_FNUMBER),
        exposureTimeSeconds = sub?.positiveDouble(ExifSubIFDDirectory.TAG_EXPOSURE_TIME),
        focalLengthMm = sub?.positiveDouble(ExifSubIFDDirectory.TAG_FOCAL_LENGTH),
        // getInteger is null for a tag of several values.
        iso = sub?.getIntArray(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT)?.firstOrNull()?.takeIf { it > 0 },
        lensMake = exifName(sub?.getString(ExifSubIFDDirectory.TAG_LENS_MAKE)),
        lensModel = exifName(sub?.getString(ExifSubIFDDirectory.TAG_LENS_MODEL)),
        exposureBiasEv = exifExposureBias(sub?.double(ExifSubIFDDirectory.TAG_EXPOSURE_BIAS)),
        exposureProgram = exifExposureProgram(sub?.getInteger(ExifSubIFDDirectory.TAG_EXPOSURE_PROGRAM)),
        meteringMode = exifMeteringMode(sub?.getInteger(ExifSubIFDDirectory.TAG_METERING_MODE)),
        flash = sub?.getInteger(ExifSubIFDDirectory.TAG_FLASH),
        whiteBalance = exifWhiteBalance(sub?.getInteger(ExifSubIFDDirectory.TAG_WHITE_BALANCE_MODE)),
        software = exifName(ifd0?.getString(ExifIFD0Directory.TAG_SOFTWARE)),
        modifiedAt = parseExifLocalDateTime(ifd0?.getString(ExifIFD0Directory.TAG_DATETIME)),
        imageDirectionDegrees = exifImageDirection(gps?.double(GpsDirectory.TAG_IMG_DIRECTION)),
        horizontalErrorMeters = exifHorizontalError(gps?.double(GpsDirectory.TAG_H_POSITIONING_ERROR)),
        focalLength35mm = exifFocalLength35mm(sub?.getInteger(ExifSubIFDDirectory.TAG_35MM_FILM_EQUIV_FOCAL_LENGTH)),
        digitalZoomRatio = exifDigitalZoom(sub?.double(ExifSubIFDDirectory.TAG_DIGITAL_ZOOM_RATIO)),
        sceneCaptureType = exifSceneCaptureType(sub?.getInteger(ExifSubIFDDirectory.TAG_SCENE_CAPTURE_TYPE)),
        customRendered = exifCustomRendered(sub?.getInteger(ExifSubIFDDirectory.TAG_CUSTOM_RENDERED)),
        colorSpace = exifColorSpace(sub?.getInteger(ExifSubIFDDirectory.TAG_COLOR_SPACE)),
        subjectDistanceMeters = exifSubjectDistance(sub?.double(ExifSubIFDDirectory.TAG_SUBJECT_DISTANCE)),
        imageDescription = exifName(ifd0?.getString(ExifIFD0Directory.TAG_IMAGE_DESCRIPTION)),
        artist = exifName(ifd0?.getString(ExifIFD0Directory.TAG_ARTIST)),
        copyright = exifName(ifd0?.getString(ExifIFD0Directory.TAG_COPYRIGHT)),
        // metadata-extractor doesn't say the byte order a UNICODE comment needs.
        userComment = exifUserComment(sub?.getByteArray(ExifSubIFDDirectory.TAG_USER_COMMENT)) { jpegExifLittleEndian(srcBytes) },
        bodySerial = exifName(sub?.getString(ExifSubIFDDirectory.TAG_BODY_SERIAL_NUMBER)),
        lensSerial = exifName(sub?.getString(ExifSubIFDDirectory.TAG_LENS_SERIAL_NUMBER)),
        gpsTime = gps?.let { dir ->
            val time = dir.getRationalArray(GpsDirectory.TAG_TIME_STAMP)?.takeIf { it.size == 3 && it.all { r -> r.denominator != 0L } }
            exifGpsTime(dir.getString(GpsDirectory.TAG_DATE_STAMP), time?.get(0)?.toDouble(), time?.get(1)?.toDouble(), time?.get(2)?.toDouble())
        },
        speedKmh = exifSpeedKmh(gps?.double(GpsDirectory.TAG_SPEED), gps?.getString(GpsDirectory.TAG_SPEED_REF)),
    )
}

private fun firstNonNullInt(vararg values: Int?): Int? =
    values.firstOrNull { it != null && it > 0 }

private fun com.drew.metadata.Directory.getRationalOrNull(tag: Int): com.drew.lang.Rational? =
    if (containsTag(tag)) getRational(tag) else null

private fun com.drew.metadata.Directory.double(tag: Int): Double? =
    getRationalOrNull(tag)?.takeIf { it.denominator != 0L }?.toDouble()

private fun com.drew.metadata.Directory.positiveDouble(tag: Int): Double? = double(tag)?.takeIf { it.isFinite() && it > 0 }
