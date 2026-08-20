package id.homebase.core.ui.screens.moments.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
import id.homebase.core.image.ImageSize
import kotlin.uuid.Uuid

// Sizing is aspect-ratio driven so the gallery fills the parent's width, unlike the chat version which clamps
// to a fixed bubble size. 2+ payloads become an Instagram-style carousel locked to the first payload's aspect.
// Default `shape` is RectangleShape — the parent is expected to clip its own outer corners.
@Composable
fun MomentMediaGallery(
    payloads: List<PayloadDescriptor>,
    fileId: Uuid,
    driveId: Uuid,
    previewThumbnail: EmbeddedThumb? = null,
    keyHeader: KeyHeader,
    modifier: Modifier = Modifier,
    onMediaClick: ((PayloadDescriptor) -> Unit)? = null,
    onMediaLongPress: ((PayloadDescriptor, Offset) -> Unit)? = null,
    shape: Shape = RectangleShape,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    messageId: Uuid,
    downloadingFiles: Set<String>,
    isUploading: Boolean = false,
    // Shared across all videos in the feed (one tap persists).
    isMuted: Boolean = true,
    onToggleMute: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    // True when the host moment is the most-centred video card in the viewport. Ignored by SingleImageLayout —
    // single videos autoplay via the parent setting playingMomentId.
    autoplayActive: Boolean = false,
    // Fires with the visible carousel page's payload key so the host can route tap-to-detail. No-op on the
    // single-payload path.
    onVisiblePayloadChanged: (String) -> Unit = {},
    // Force carousel videos to fit — set while the host card is shrunk for the comments sheet.
    fitToContent: Boolean = false,
    // Optional floor on a single image's aspect: clamps very tall portraits (center-cropped) so one post can't
    // dominate the scroll. Null = no floor; the feed passes FeedMinMediaAspect.
    minAspect: Float? = null,
    // When set, the bytes live on this followed author's drive: read over peer by [globalTransitId] from
    // [driveId]. Both must be set together. Feed-only; Moments passes null.
    remoteOdinId: OdinId? = null,
    globalTransitId: Uuid? = null,
) {
    if (payloads.isEmpty()) return

    Box(modifier = modifier.fillMaxWidth().clip(shape)) {
        if (payloads.size == 1) {
            SingleImageLayout(
                payload = payloads[0],
                fileId = fileId,
                driveId = driveId,
                keyHeader = keyHeader,
                previewThumbnail = previewThumbnail
                    ?: payloads[0].previewThumbnail?.toEmbeddedThumb(),
                onMediaClick = onMediaClick,
                onMediaLongPress = onMediaLongPress,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                messageId = messageId,
                downloadingFiles = downloadingFiles,
                isUploading = isUploading,
                fitToContent = fitToContent,
                minAspect = minAspect,
                remoteOdinId = remoteOdinId,
                globalTransitId = globalTransitId,
            )
        } else {
            MomentMediaCarousel(
                payloads = payloads,
                fileId = fileId,
                driveId = driveId,
                previewThumbnail = previewThumbnail,
                keyHeader = keyHeader,
                messageId = messageId,
                downloadingFiles = downloadingFiles,
                isUploading = isUploading,
                isMuted = isMuted,
                onToggleMute = onToggleMute,
                onMediaClick = onMediaClick,
                onMediaLongPress = onMediaLongPress,
                onDoubleTap = onDoubleTap,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                autoplayActive = autoplayActive,
                onVisiblePayloadChanged = onVisiblePayloadChanged,
                fitToContent = fitToContent,
                remoteOdinId = remoteOdinId,
                globalTransitId = globalTransitId,
            )
        }
    }
}

@Composable
private fun SingleImageLayout(
    payload: PayloadDescriptor,
    fileId: Uuid,
    driveId: Uuid,
    keyHeader: KeyHeader,
    previewThumbnail: EmbeddedThumb?,
    onMediaClick: ((PayloadDescriptor) -> Unit)?,
    onMediaLongPress: ((PayloadDescriptor, Offset) -> Unit)?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    messageId: Uuid,
    downloadingFiles: Set<String>,
    isUploading: Boolean,
    // Show the whole image (fit) filling the host box instead of the aspect-locked crop — used while the card
    // is shrunk to a band above the comments sheet.
    fitToContent: Boolean = false,
    // Optional lower bound on the aspect (see [MomentMediaGallery]).
    minAspect: Float? = null,
    // Over-peer read identity for followed-post media; null = local.
    remoteOdinId: OdinId? = null,
    globalTransitId: Uuid? = null,
) {
    // Computed from thumbnail metadata so the cell sizes before the (possibly remote, encrypted) full image is
    // decoded. The photo is drawn ContentScale.Fit by the zoom wrapper, so the cell must match the photo's real
    // aspect or Fit leaves blank bars. Only extreme panoramas are clamped to [MaxFeedPhotoAspect]; a non-null
    // [minAspect] (feed only) also floors very tall portraits.
    val aspect = (aspectRatioFor(payload) ?: 1f)
        .coerceAtMost(MaxFeedPhotoAspect)
        .let { if (minAspect != null) it.coerceAtLeast(minAspect) else it }

    // TEMPORARY: a portrait photo renders correctly and then re-crops. Keyed on `aspect`, so a second line for
    // one payload is the bug reproducing.
    LaunchedEffect(payload.key, aspect) {
        val preview = payload.previewThumbnail
        val largest = payload.thumbnails?.lastOrNull()
        Logger.i(tag = "MomentAspect") {
            "key=${payload.key} aspect=$aspect " +
                "preview=${preview?.pixelWidth}x${preview?.pixelHeight} " +
                "largestThumb=${largest?.pixelWidth}x${largest?.pixelHeight} " +
                "thumbCount=${payload.thumbnails?.size ?: 0} " +
                "source=${if (preview != null) "preview" else if (largest != null) "thumbnail" else "FALLBACK_1:1"}"
        }
    }

    // Feed only (minAspect != null): cap the photo's height so a tall post fits a screenful, drawn Fit and
    // scaled down rather than cropped. Moments keeps the natural-aspect height.
    val maxMediaHeight = if (minAspect != null) {
        with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } *
            FeedMediaMaxScreenFraction
    } else {
        Dp.Unspecified
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // When capped, the whole image is drawn Fit inside (letterboxed at the sides), never cropped.
        val cellModifier = if (fitToContent) {
            Modifier.fillMaxSize()
        } else {
            val naturalHeight = maxWidth / aspect
            val cellHeight =
                if (maxMediaHeight != Dp.Unspecified) minOf(naturalHeight, maxMediaHeight)
                else naturalHeight
            Modifier.fillMaxWidth().height(cellHeight)
        }

        MomentMediaItem(
            payload = payload,
            fileId = fileId,
            driveId = driveId,
            keyHeader = keyHeader,
            previewThumbnail = previewThumbnail,
            modifier = cellModifier,
            imageSize = ImageSize.THUMB_LARGE,
            // fitBounds routes MomentMediaItem to ContentScale.Fit and makes it honour [cellModifier] instead of
            // re-imposing the image's intrinsic aspect ratio.
            preserveAspectRatio = fitToContent,
            fitBounds = fitToContent || minAspect != null,
            shape = RectangleShape,
            // Preserve nullability: wrapping a nullable handler in a non-null lambda made the item ALWAYS
            // register a pointer detector that silently consumed taps, breaking the card-level multi-tap
            // detector. Same pattern at the other layout call sites.
            onClick = onMediaClick?.let { handler -> { handler(payload) } },
            onLongPress = onMediaLongPress?.let { handler -> { offset -> handler(payload, offset) } },
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            isDownloading = downloadingFiles.contains("${messageId}_${payload.key}"),
            messageId = messageId,
            isUploading = isUploading,
            remoteOdinId = remoteOdinId,
            globalTransitId = globalTransitId,
            // Feed uses the non-zoom Fit path (loads a thumbnail, not the full payload); Moments keeps pinch-zoom.
            enableZoom = minAspect == null,
        )
    }
}

// Returns null when no thumbnail with sane dimensions is available — caller decides the fallback.
// Crop cap for the single-VIDEO feed tile. 0.8 == a 4:5 portrait frame, center-cropped so it reads as a card
// instead of a short strip; taller portrait videos keep their natural height. Photos use [MaxFeedPhotoAspect].
internal const val MaxFeedMediaAspect = 0.8f

// Upper bound on a single-PHOTO feed cell's aspect. 1.91 ≈ Instagram's widest landscape. The photo is drawn
// Fit, so the cell tracks its real aspect and Fit fills it exactly; only wider panoramas letterbox slightly.
internal const val MaxFeedPhotoAspect = 1.91f

// Feed only: a single photo's height is capped to this fraction of the window so a tall post fits a screenful.
// 0.7 leaves room for the header/caption/actions. Tune here.
internal const val FeedMediaMaxScreenFraction = 0.7f

internal fun aspectRatioFor(payload: PayloadDescriptor): Float? {
    val thumb = payload.previewThumbnail ?: payload.thumbnails?.lastOrNull()
    val w = thumb?.pixelWidth
    val h = thumb?.pixelHeight
    if (w == null || h == null || w <= 0 || h <= 0) return null
    return w.toFloat() / h.toFloat()
}
