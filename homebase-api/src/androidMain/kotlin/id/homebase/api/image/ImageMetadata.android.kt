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
    )
}
