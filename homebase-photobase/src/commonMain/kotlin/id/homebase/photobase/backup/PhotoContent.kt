package id.homebase.photobase.backup

import id.homebase.api.image.ImageMetadata
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable

/** The header content of a photo file, encrypted with it. The capture date rides in `userDate`, the id in `uniqueId`. */
@Serializable
data class PhotoContent(
    val v: Int = CURRENT_VERSION,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** EXIF wall-clock without an offset is read in the phone's zone, the same guess the camera made. */
fun captureTimeMs(metadata: ImageMetadata?, fallbackMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Long {
    val capturedAt = metadata?.capturedAt ?: return fallbackMs
    val offset = metadata.captureUtcOffset
    val instant = if (offset != null) capturedAt.toInstant(offset) else capturedAt.toInstant(zone)
    return instant.toEpochMilliseconds()
}
