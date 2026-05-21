package id.homebase.api.image

import androidx.exifinterface.media.ExifInterface
import co.touchlab.kermit.Logger
import java.io.ByteArrayInputStream

actual fun readImageMetadata(srcBytes: ByteArray): ImageMetadata? {
    return try {
        val exif = ExifInterface(ByteArrayInputStream(srcBytes))

        val capturedAt = parseExifLocalDateTime(
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        )
        val offset = parseExifUtcOffset(
            exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)
        )

        val latLong = exif.latLong
        val altitude = if (exif.hasAttribute(ExifInterface.TAG_GPS_ALTITUDE)) {
            exif.getAltitude(Double.NaN).takeUnless { it.isNaN() }
        } else null

        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED
        ).takeIf { it in 1..8 }

        val pixelWidth = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 }
        val pixelHeight = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }

        ImageMetadata(
            capturedAt = capturedAt,
            captureUtcOffset = offset,
            latitude = latLong?.get(0),
            longitude = latLong?.get(1),
            altitudeMeters = altitude,
            orientation = orientation,
            pixelWidth = pixelWidth,
            pixelHeight = pixelHeight,
            cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() },
            cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
        )
    } catch (e: Throwable) {
        Logger.w(throwable = e, tag = "readImageMetadata") { "Android EXIF read failed" }
        null
    }
}
