package id.homebase.api.image

import androidx.exifinterface.media.ExifInterface
import co.touchlab.kermit.Logger
import java.io.ByteArrayInputStream

actual fun readImageMetadata(srcBytes: ByteArray): ImageMetadata? = try {
    ExifInterface(ByteArrayInputStream(srcBytes)).toImageMetadata()
} catch (e: Throwable) {
    Logger.w(throwable = e, tag = "readImageMetadata") { "Android EXIF read failed" }
    null
}

internal fun ExifInterface.toImageMetadata(): ImageMetadata {
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
    )
}

private fun ExifInterface.double(tag: String): Double? =
    if (hasAttribute(tag)) getAttributeDouble(tag, Double.NaN).takeIf { !it.isNaN() } else null

private fun ExifInterface.int(tag: String): Int? = if (hasAttribute(tag)) getAttributeInt(tag, -1).takeIf { it >= 0 } else null

private fun ExifInterface.positiveDouble(tag: String): Double? = double(tag)?.takeIf { it.isFinite() && it > 0 }
