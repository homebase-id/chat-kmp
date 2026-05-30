package id.homebase.core.ui.screens.moments.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.core.image.ImageSize
import kotlin.uuid.Uuid

/**
 * Moments-specific clone of `id.homebase.chat.widget.MediaGallery`.
 *
 * Sizing is aspect-ratio driven so the gallery always fills the parent
 * container's width — unlike the chat version, which clamps to a fixed
 * chat-bubble width and height. Per-count layouts:
 *
 *  - **1**: full-width cell whose aspect ratio matches the payload's preview
 *    thumbnail (falls back to 1:1 when no thumbnail metadata is available).
 *  - **2+**: Instagram-style horizontal swipe carousel via [MomentMediaCarousel].
 *    Each payload is a swipeable page; videos play in place. The whole
 *    carousel is locked to the first payload's aspect (later items crop to
 *    fit), matching Instagram's multi-image post behaviour.
 *
 * Default `shape` is [RectangleShape] — the parent (e.g. moment post card) is
 * expected to clip its own outer rounded corners. Pass a [Shape] explicitly
 * if the gallery is the only clipper.
 */
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
    // Mute toggle is shared across all videos in the feed (one tap persists).
    // Owned by the list, threaded through the post card for the single-video
    // case and into the carousel for the multi-payload case.
    isMuted: Boolean = true,
    onToggleMute: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    // True when the host moment is currently the most-centred video card in
    // the viewport. The carousel uses it to autoplay whichever page is
    // visible (if that page is a video). Ignored by SingleImageLayout —
    // single videos autoplay via the parent setting playingMomentId.
    autoplayActive: Boolean = false,
    // Fires with the payload key of the currently-visible carousel page so
    // the host can route tap-to-detail to the right page. No-op on the
    // single-payload path (the host already knows the only payload's key).
    onVisiblePayloadChanged: (String) -> Unit = {},
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
) {
    // Compute aspect from the payload's thumbnail metadata so the cell sizes
    // before the (possibly remote, encrypted) full image is decoded. Falls
    // back to 1:1 when no thumbnail data is present. Capped at
    // [MaxFeedMediaAspect] so a wide landscape doesn't render as a thin strip
    // — the cell stays a comfortable height and ContentScale.Crop
    // (preserveAspectRatio = false, below) fills it, trimming the far edges.
    val aspect = (aspectRatioFor(payload) ?: 1f).coerceAtMost(MaxFeedMediaAspect)

    MomentMediaItem(
        payload = payload,
        fileId = fileId,
        driveId = driveId,
        keyHeader = keyHeader,
        previewThumbnail = previewThumbnail,
        modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
        imageSize = ImageSize.THUMB_LARGE,
        // Aspect set on the modifier — let the image fill it (Crop is a no-op
        // when source aspect matches the box).
        preserveAspectRatio = false,
        shape = RectangleShape,
        // Preserve nullability so MomentMediaItem only installs its inner
        // pointerInput when there's an actual click/long-press handler.
        // Wrapping a nullable handler in a non-null `{ onMediaClick?.invoke(...) }`
        // lambda made the item *always* register a pointer detector that
        // silently consumed taps — which broke the feed's card-level
        // multi-tap detector. Same pattern at the other layout call sites.
        onClick = onMediaClick?.let { handler -> { handler(payload) } },
        onLongPress = onMediaLongPress?.let { handler -> { offset -> handler(payload, offset) } },
        sharedTransitionScope = sharedTransitionScope,
        animatedVisibilityScope = animatedVisibilityScope,
        isDownloading = downloadingFiles.contains("${messageId}_${payload.key}"),
        messageId = messageId,
        isUploading = isUploading,
    )
}

/**
 * Best-effort aspect ratio (`width / height`) from a payload's thumbnail
 * metadata. Returns `null` when no thumbnail with sane dimensions is
 * available — caller decides the fallback.
 */
/**
 * Upper bound on a feed cell's width/height ratio. 0.8 == a 4:5 portrait
 * frame: any photo at least as wide as 4:5 (landscape, square, and mildly
 * portrait shots) is sized to this tall frame and center-cropped
 * (ContentScale.Crop) so it reads as a substantial card instead of a short
 * horizontal strip. Taller portraits (ratio < 0.8) keep their natural height.
 * Detail/full-screen views are unaffected — they size media independently.
 */
internal const val MaxFeedMediaAspect = 0.8f

internal fun aspectRatioFor(payload: PayloadDescriptor): Float? {
    val thumb = payload.previewThumbnail ?: payload.thumbnails?.lastOrNull()
    val w = thumb?.pixelWidth
    val h = thumb?.pixelHeight
    if (w == null || h == null || w <= 0 || h <= 0) return null
    return w.toFloat() / h.toFloat()
}
