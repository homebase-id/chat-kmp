package id.homebase.api.image

import androidx.exifinterface.media.ExifInterface
import co.touchlab.kermit.Logger
import java.io.ByteArrayInputStream

actual fun readImageMetadata(srcBytes: ByteArray): ImageMetadata? = try {
    ExifInterface(ByteArrayInputStream(srcBytes)).toImageMetadata { jpegExifLittleEndian(srcBytes) }
} catch (e: Throwable) {
    Logger.w(throwable = e, tag = "readImageMetadata") { "Android EXIF read failed" }
    null
}

/** [littleEndian]: the EXIF's byte order where known, which ExifInterface doesn't say; a UNICODE comment needs it. */
internal fun ExifInterface.toImageMetadata(littleEndian: () -> Boolean?): ImageMetadata {
    val capturedAt = parseExifLocalDateTime(
        getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: getAttribute(ExifInterface.TAG_DATETIME)
    )
    val offset = parseExifUtcOffset(
        getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            ?: getAttribute(ExifInterface.TAG_OFFSET_TIME)
    )

    val altitude = if (hasAttribute(ExifInterface.TAG_GPS_ALTITUDE)) {
        getAltitude(Double.NaN).takeUnless { it.isNaN() }
    } else null

    val orientation = getAttributeInt(
        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED
    ).takeIf { it in 1..8 }

    val pixelWidth = getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 }
    val pixelHeight = getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }

    return ImageMetadata(
        capturedAt = capturedAt,
        captureUtcOffset = offset,
        latitude = latLong?.get(0),
        longitude = latLong?.get(1),
        altitudeMeters = altitude,
        orientation = orientation,
        pixelWidth = pixelWidth,
        pixelHeight = pixelHeight,
        cameraMake = getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() },
        cameraModel = getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
        fNumber = positiveDouble(ExifInterface.TAG_F_NUMBER),
        exposureTimeSeconds = positiveDouble(ExifInterface.TAG_EXPOSURE_TIME),
        focalLengthMm = positiveDouble(ExifInterface.TAG_FOCAL_LENGTH),
        // getAttributeInt gives up on a tag of several values; the attribute is them joined by commas.
        iso = getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.substringBefore(',')?.trim()?.toIntOrNull()?.takeIf { it > 0 },
        lensMake = exifName(getAttribute(ExifInterface.TAG_LENS_MAKE)),
        lensModel = exifName(getAttribute(ExifInterface.TAG_LENS_MODEL)),
        exposureBiasEv = exifExposureBias(double(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)),
        exposureProgram = exifExposureProgram(int(ExifInterface.TAG_EXPOSURE_PROGRAM)),
        meteringMode = exifMeteringMode(int(ExifInterface.TAG_METERING_MODE)),
        flash = int(ExifInterface.TAG_FLASH),
        whiteBalance = exifWhiteBalance(int(ExifInterface.TAG_WHITE_BALANCE)),
        software = exifName(getAttribute(ExifInterface.TAG_SOFTWARE)),
        modifiedAt = parseExifLocalDateTime(getAttribute(ExifInterface.TAG_DATETIME)),
        imageDirectionDegrees = exifImageDirection(double(ExifInterface.TAG_GPS_IMG_DIRECTION)),
        horizontalErrorMeters = exifHorizontalError(double(ExifInterface.TAG_GPS_H_POSITIONING_ERROR)),
        focalLength35mm = exifFocalLength35mm(int(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)),
        digitalZoomRatio = exifDigitalZoom(double(ExifInterface.TAG_DIGITAL_ZOOM_RATIO)),
        sceneCaptureType = exifSceneCaptureType(int(ExifInterface.TAG_SCENE_CAPTURE_TYPE)),
        customRendered = exifCustomRendered(int(ExifInterface.TAG_CUSTOM_RENDERED)),
        colorSpace = exifColorSpace(int(ExifInterface.TAG_COLOR_SPACE)),
        subjectDistanceMeters = exifSubjectDistance(double(ExifInterface.TAG_SUBJECT_DISTANCE)),
        imageDescription = exifName(getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)),
        artist = exifName(getAttribute(ExifInterface.TAG_ARTIST)),
        copyright = exifName(getAttribute(ExifInterface.TAG_COPYRIGHT)),
        userComment = exifUserComment(getAttributeBytes(ExifInterface.TAG_USER_COMMENT), littleEndian),
        bodySerial = exifName(getAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER)),
        lensSerial = exifName(getAttribute(ExifInterface.TAG_LENS_SERIAL_NUMBER)),
        // The raw rationals: getAttribute writes a 0/0 part as "00", a real-looking time the other platforms reject.
        gpsTime = gpsTimeStamp()?.let { (h, m, s) -> exifGpsTime(getAttribute(ExifInterface.TAG_GPS_DATESTAMP), h, m, s) },
        speedKmh = exifSpeedKmh(double(ExifInterface.TAG_GPS_SPEED), getAttribute(ExifInterface.TAG_GPS_SPEED_REF)),
    )
}

private fun ExifInterface.double(tag: String): Double? =
    if (hasAttribute(tag)) getAttributeDouble(tag, Double.NaN).takeIf { !it.isNaN() } else null

private fun ExifInterface.int(tag: String): Int? = if (hasAttribute(tag)) getAttributeInt(tag, -1).takeIf { it >= 0 } else null

private fun ExifInterface.positiveDouble(tag: String): Double? = double(tag)?.takeIf { it.isFinite() && it > 0 }

// Three RATIONALs. No look-up of the byte order, which would read the file again for every photo: these are small
// numbers, so the order that gives a time of day is the file's; null for x/0.
private fun ExifInterface.gpsTimeStamp(): Triple<Double, Double, Double>? {
    val raw = getAttributeBytes(ExifInterface.TAG_GPS_TIMESTAMP)?.takeIf { it.size == 24 } ?: return null
    fun read(le: Boolean): Triple<Double, Double, Double>? {
        fun u32(at: Int): Long = (0 until 4).fold(0L) { acc, i -> acc or ((raw[at + i].toLong() and 0xFF) shl (8 * if (le) i else 3 - i)) }
        val parts = (0 until 3).map { i -> u32(i * 8 + 4).takeIf { it != 0L }?.let { u32(i * 8).toDouble() / it } ?: return null }
        return Triple(parts[0], parts[1], parts[2]).takeIf { (h, m, s) -> h < 24 && m < 60 && s < 61 }
    }
    return read(le = true) ?: read(le = false)
}
