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
    )
}

private fun firstNonNullInt(vararg values: Int?): Int? =
    values.firstOrNull { it != null && it > 0 }

private fun com.drew.metadata.Directory.getRationalOrNull(tag: Int): com.drew.lang.Rational? =
    if (containsTag(tag)) getRational(tag) else null
