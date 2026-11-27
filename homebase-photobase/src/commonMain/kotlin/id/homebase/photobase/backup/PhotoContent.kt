package id.homebase.photobase.backup

import id.homebase.api.image.ImageMetadata
import id.homebase.api.media.MediaLocation
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
    /**
     * When userDate was set by hand, which then wins over the original's EXIF time; [offsetSeconds] is the zone it was
     * set in. A time, not a flag, so a later change to the file isn't taken for the move of day this was.
     */
    val timeSetAtMs: Long? = null,
    val offsetSeconds: Int? = null,
    /** The place was set by hand: the original's GPS details no longer describe it. */
    val placeByHand: Boolean? = null,
) {
    val location: MediaLocation?
        get() = if (latitude != null && longitude != null) MediaLocation(latitude, longitude, altitude) else null

    companion object {
        const val CURRENT_VERSION = 1

        fun at(location: MediaLocation?) = PhotoContent(latitude = location?.latitude, longitude = location?.longitude, altitude = location?.altitude)
    }
}

/** EXIF wall-clock without an offset is read in the phone's zone, the same guess the camera made. */
fun captureTimeMs(metadata: ImageMetadata?, fallbackMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Long {
    val capturedAt = metadata?.capturedAt ?: return fallbackMs
    val offset = metadata.captureUtcOffset
    val instant = if (offset != null) capturedAt.toInstant(offset) else capturedAt.toInstant(zone)
    return instant.toEpochMilliseconds()
}
