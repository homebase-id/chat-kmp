package id.homebase.core.image

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
import kotlin.math.max
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
    /**
     * Native sizes of the server-side thumbnails this image actually has, taken
     * from the payload descriptor. The loader snaps each measured display size to
     * the best of these via [selectThumbSize] so the request — and therefore the
     * disk cache key — matches what the sender seeded under the optimistic fileId
     * (and what every other device requests), instead of minting a fresh cache
     * entry per measured size. Empty when the caller has no descriptor (link
     * previews, avatars), in which case the measured size is requested unchanged.
     */
    val availableThumbSizes: List<ImageSize> = emptyList(),
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
    /**
     * When non-null, the payload lives on a FOLLOWED identity's drive and is fetched over peer
     * (by [globalTransitId]) instead of locally — see [HomebaseImageLoader]. [driveId] is then the
     * author's channel drive and [remoteOdinId] is the author. Null for ordinary local reads.
     */
    val remoteOdinId: OdinId? = null,
    /** Global transit id of a remote post, required alongside [remoteOdinId] for the over-peer fetch. */
    val globalTransitId: Uuid? = null,
    /** Type of the author's drive ([driveId] is the alias) — both go in the over-peer transit query. */
    val remoteDriveType: Uuid? = null,
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

/**
 * Map a payload's [ThumbnailDescriptor] list to the concrete native [ImageSize]s
 * the server actually stores, dropping the embedded preview thumbnail (carried
 * separately) and any descriptor with missing or non-positive dimensions.
 */
fun thumbSizesFrom(thumbnails: List<ThumbnailDescriptor>?): List<ImageSize> =
    thumbnails.orEmpty().mapNotNull { t ->
        val w = t.pixelWidth ?: return@mapNotNull null
        val h = t.pixelHeight ?: return@mapNotNull null
        if (w <= 0 || h <= 0) null else ImageSize(w, h)
    }

/**
 * Pick which thumbnail size to actually request for a given measured display size.
 *
 * The server only stores thumbnails at the sender's native sizes; asking for an
 * arbitrary size returns the nearest native thumbnail's bytes regardless. Snapping
 * to a native size makes the disk cache key honest — one entry per native size,
 * shared across every measured size and device — and lets it match the entry the
 * sender seeded under the optimistic fileId, so a freshly sent image shows its
 * sharp thumbnail through "finalizing" instead of the blurry embedded preview.
 *
 * Selection compares on the longest edge (consistent with thumbnail generation's
 * `maxPixelDimension`): the smallest native whose longest edge covers the request
 * (never upscale → no added blur), clamped to the largest native when the request
 * exceeds all of them. Returns [requested] unchanged when [available] is empty so
 * callers without a descriptor keep requesting an arbitrary size.
 */
fun selectThumbSize(requested: ImageSize, available: List<ImageSize>): ImageSize {
    if (available.isEmpty()) return requested
    val reqMax = max(requested.pixelWidth, requested.pixelHeight)
    return available.filter { max(it.pixelWidth, it.pixelHeight) >= reqMax }
        .minByOrNull { max(it.pixelWidth, it.pixelHeight) }
        ?: available.maxByOrNull { max(it.pixelWidth, it.pixelHeight) }!!
}
