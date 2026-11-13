package id.homebase.core.image

import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
import id.homebase.api.image.MediaQuality
import id.homebase.core.util.isWeb
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
    /** The server's thumbnails for this payload; empty without a descriptor (link previews, avatars). */
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
    /** The payload's size, which decides whether a view may step up to it. */
    val payloadBytes: Long? = null,
    /** The payload's pixels, when the sender recorded them; a view steps up only to a payload it knows. */
    val finalImageSize: ImageSize? = null,
    /** The payload is the sender's untouched file, which a view never loads in place of a thumbnail. */
    val payloadIsUntouchedFile: Boolean = false,
    /** Set with [forGridTile]: a looser allowance, and never the payload. */
    val gridTile: Boolean = false,
    /** Set with [globalTransitId] to fetch over peer; both null for local media. */
    val remoteOdinId: OdinId? = null,
    val globalTransitId: Uuid? = null,
) {
    val isOverPeer: Boolean
        get() = remoteOdinId != null && globalTransitId != null

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

        /** Derives payloadKey/contentType/thumb sizes/preview/lastModified from [descriptor] so a caller can't forget payloadContentType and 404 a GIF's never-generated thumbnail. */
        fun from(
            driveId: Uuid,
            fileId: Uuid,
            descriptor: PayloadDescriptor,
            keyHeader: KeyHeader,
            previewThumbnail: EmbeddedThumb? = descriptor.previewThumbnail?.toEmbeddedThumb(),
            availableThumbSizes: List<ImageSize> = thumbSizesFrom(descriptor.thumbnails),
            loadFullPayload: Boolean = false,
            isEncrypted: Boolean = true,
            lastModified: Long? = descriptor.lastModified,
            pendingFileUri: String? = null,
            payloadContentType: String? = descriptor.contentType,
            payloadBytes: Long? = descriptor.bytesWritten,
            remoteOdinId: OdinId? = null,
            globalTransitId: Uuid? = null,
        ): HomebaseImageData {
            val recorded = descriptor.imageFile()
            return HomebaseImageData(
                driveId = driveId,
                fileId = fileId,
                payloadKey = descriptor.key,
                previewThumbnail = previewThumbnail,
                availableThumbSizes = availableThumbSizes,
                loadFullPayload = loadFullPayload,
                isEncrypted = isEncrypted,
                lastModified = lastModified,
                pendingFileUri = pendingFileUri,
                payloadContentType = payloadContentType,
                keyHeader = keyHeader,
                payloadBytes = payloadBytes,
                finalImageSize = recorded?.let { image ->
                    ImageSize(image.pixelWidth ?: return@let null, image.pixelHeight ?: return@let null)
                },
                payloadIsUntouchedFile = recorded?.original == true || recorded?.quality == MediaQuality.ORIGINAL,
                remoteOdinId = remoteOdinId,
                globalTransitId = globalTransitId,
            )
        }
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

// Well under PayloadSizePolicy.RENDER_LIMIT_BYTES: every view that asks pulls the payload.
private const val STEP_UP_LIMIT_BYTES = 12L * 1024 * 1024

/** Whether no thumbnail covers [view] and the payload is a final image we know we made, with more pixels. */
fun shouldStepUpToPayload(view: ImageSize, data: HomebaseImageData, crop: Boolean = false): Boolean {
    if (!data.mayStepUp) return false
    if (data.availableThumbSizes.any { coversView(it, view, crop, data.shortfallPercent) }) return false
    val largest = data.availableThumbSizes.maxOf { max(it.pixelWidth, it.pixelHeight) }
    val final = data.finalImageSize ?: return false
    if (max(final.pixelWidth, final.pixelHeight) <= largest) return false
    val payloadBytes = data.payloadBytes ?: return false
    return payloadBytes <= STEP_UP_LIMIT_BYTES && data.payloadContentType in ImageFormatDetector.TYPES_EVERY_CLIENT_CAN_DECODE
}

private val TYPES_WEB_CANNOT_DECODE = setOf("image/heic", "image/heif")

fun HomebaseImageData.payloadDecodesHere(): Boolean = !(isWeb() && payloadContentType in TYPES_WEB_CANNOT_DECODE)

/** The full payload where it decodes, otherwise the largest thumbnail stands in for it. */
fun HomebaseImageData.forFullScreen(): HomebaseImageData =
    if (loadFullPayload && !payloadDecodesHere() && availableThumbSizes.isNotEmpty()) copy(loadFullPayload = false) else this

/** Bubbles, posts and viewers: a photo may be drawn up to 10% larger than its pixels. */
const val DETAIL_SHORTFALL_PERCENT = 10

/** A tile among hundreds is glanced at, so a phone grid stays on the smallest thumbnail. */
const val GRID_SHORTFALL_PERCENT = 40

/** Whether [image], drawn into [view] filling it ([crop]) or fitting inside it, is stretched by no more than [shortfallPercent]. */
fun coversView(image: ImageSize, view: ImageSize, crop: Boolean, shortfallPercent: Int): Boolean {
    val keep = 100L - shortfallPercent
    val wide = image.pixelWidth * 100L >= view.pixelWidth * keep
    val tall = image.pixelHeight * 100L >= view.pixelHeight * keep
    return if (crop) wide && tall else wide || tall
}

/**
 * The smallest native thumbnail that covers [view] (see [coversView]), else the largest. Snapping to a
 * native size keeps one disk cache entry per thumbnail, shared with the sender's optimistic seed.
 * Returns [view] when [available] is empty, so callers without a descriptor ask for what they measured.
 */
fun selectThumbSize(
    view: ImageSize,
    available: List<ImageSize>,
    crop: Boolean = false,
    shortfallPercent: Int = DETAIL_SHORTFALL_PERCENT,
): ImageSize {
    if (available.isEmpty()) return view
    val smallestFirst = available.sortedBy { max(it.pixelWidth, it.pixelHeight) }
    return smallestFirst.firstOrNull { coversView(it, view, crop, shortfallPercent) } ?: smallestFirst.last()
}

fun HomebaseImageData.forGridTile(): HomebaseImageData = copy(gridTile = true)

val HomebaseImageData.shortfallPercent: Int get() = if (gridTile) GRID_SHORTFALL_PERCENT else DETAIL_SHORTFALL_PERCENT

/**
 * Only a final image whose size the sender recorded and that isn't their original: anything else
 * (Photos, Vault, stickers, web posts, older messages) may be an untouched file of any size.
 */
val HomebaseImageData.mayStepUp: Boolean
    get() = finalImageSize != null && !payloadIsUntouchedFile && !gridTile && !loadFullPayload &&
        !isPending && availableThumbSizes.isNotEmpty()
