package id.homebase.core.image

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.upload.EmbeddedThumb
import kotlin.uuid.Uuid

/**
 * Data class encapsulating all information needed to load a Homebase image.
 *
 * Supports progressive loading: tinyThumb → thumbnail → full payload
 */
data class HomebaseImageData(
    /** Drive containing the file */
    val driveId: Uuid,
    /** File ID of the image */
    val fileId: Uuid,
    /** Payload key for the image content */
    val payloadKey: String,
    /** Embedded tiny preview thumbnail (base64) for instant display */
    val previewThumbnail: EmbeddedThumb? = null,
    /** Desired resolution for thumbnail loading */
    val requestedSize: ImageSize? = null,
    /** If true, load full resolution payload instead of thumbnail */
    val loadFullPayload: Boolean = false,
    /** Whether the image is encrypted */
    val isEncrypted: Boolean = true,
    /** Last modification timestamp for cache validation */
    val lastModified: Long? = null,
    /** Local pending file (for images being uploaded/sent) */
    val pendingFileUri: String? = null,
    /**
     * Content type of the actual payload (e.g. "image/gif"), as declared on the
     * file/payload descriptor. This is distinct from [contentTypeHint], which
     * reflects the embedded preview thumbnail's content type — and that is
     * always "image/webp" even for GIFs (the sender ships a tiny WebP preview,
     * never a GIF thumbnail). The loader needs the real payload type to
     * recognise thumbless formats (GIF) and load the animated original instead
     * of requesting a server thumbnail that was never generated.
     */
    val payloadContentType: String? = null,
    /** KeyHeader for decryption of the payload */
    val keyHeader: KeyHeader,
) {
    companion object {
        /** Create data for a pending (not yet uploaded) image */
        fun pending(
            fileUri: String, previewThumbnail: EmbeddedThumb? = null
        ): HomebaseImageData = HomebaseImageData(
            driveId = Uuid.NIL,
            fileId = Uuid.NIL,
            payloadKey = "",
            previewThumbnail = previewThumbnail,
            pendingFileUri = fileUri,
            keyHeader = KeyHeader.newRandom16(),
        )
    }

    /** Whether this is a pending/local file not yet uploaded */
    val isPending: Boolean
        get() = pendingFileUri != null

    /** Content type hint from preview thumbnail */
    val contentTypeHint: String?
        get() = previewThumbnail?.contentType

    /**
     * Best-known content type of the underlying payload, used to decide how to
     * load the image (thumbnail vs. full animated original). Prefers the real
     * [payloadContentType] from the descriptor and falls back to the preview
     * thumbnail's type. Callers that only have a preview thumbnail keep their
     * existing behaviour; callers that know the payload type (e.g. an inline
     * GIF in a chat bubble) get the correct thumbless treatment.
     */
    val effectiveContentType: String?
        get() = payloadContentType ?: contentTypeHint
}

/** Represents image dimensions */
data class ImageSize(val pixelWidth: Int, val pixelHeight: Int) {
    /** Total pixel count for comparison */
    val pixelCount: Int
        get() = pixelWidth * pixelHeight

    /** Check if this size is larger or equal to another */
    fun isLargerOrEqualTo(other: ImageSize?): Boolean {
        if (other == null) return true
        return pixelWidth >= other.pixelWidth && pixelHeight >= other.pixelHeight
    }

    companion object {
        /** Preset thumbnail sizes matching server defaults */
        val THUMB_SMALL = ImageSize(320, 320)
        val THUMB_MEDIUM = ImageSize(640, 640)
        val THUMB_LARGE = ImageSize(1080, 1080)
        val THUMB_XLARGE = ImageSize(1600, 1600)
    }
}
