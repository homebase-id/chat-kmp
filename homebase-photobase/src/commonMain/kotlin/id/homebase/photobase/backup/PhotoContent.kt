package id.homebase.photobase.backup

import id.homebase.api.image.ImageMetadata
import id.homebase.api.serialization.Base64Bytes
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable

/**
 * The header content of a photo file, encrypted with it. The capture date rides in `userDate`, the
 * id in `uniqueId`. The tiny preview lives here rather than in the plaintext previewThumbnail
 * field, so the drive stays zero knowledge.
 */
@Serializable
data class PhotoContent(
    val v: Int = CURRENT_VERSION,
    val name: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val tiny: Base64Bytes? = null,
    val tinyWidth: Int? = null,
    val tinyHeight: Int? = null,
    val tinyType: String? = null,
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
