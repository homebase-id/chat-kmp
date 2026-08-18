package id.homebase.core.ui.screens.moments.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import co.touchlab.kermit.Logger
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
    // Force carousel videos to show the whole frame (fit) — set while the host
    // card is shrunk for the comments sheet.
    fitToContent: Boolean = false,
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
    // When true, show the whole image (fit) filling the host box instead of the
    // aspect-locked crop — used while the card is shrunk to a band above the
    // comments sheet so the entire photo is visible.
    fitToContent: Boolean = false,
) {
    // Compute aspect from the payload's thumbnail metadata so the cell sizes
    // before the (possibly remote, encrypted) full image is decoded. Falls
    // back to 1:1 when no thumbnail data is present. The photo is drawn
    // ContentScale.Fit by the inline zoom wrapper (enableZoom below), so the
    // cell must match the photo's real aspect or Fit leaves blank bars — the
    // landscape letterbox of #873 (and the mirror-image side-crop of #818 back
    // when this was Crop into a fixed-tall cell). Size to the natural aspect;
    // clamp only extreme panoramas to [MaxFeedPhotoAspect] so a very wide shot
    // doesn't collapse into a thin strip. Tall portraits keep their height.
    val aspect = (aspectRatioFor(payload) ?: 1f).coerceAtMost(MaxFeedPhotoAspect)

    // TEMPORARY (#1128-adjacent): a portrait photo renders correctly on web and then
    // re-crops. The cell height is locked from thumbnail metadata, so log which source
    // supplied it and what it resolved to. Keyed on `aspect`, so this fires on the initial
    // value and again on every CHANGE — a second line for one payload is the bug reproducing.
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

    MomentMediaItem(
        payload = payload,
        fileId = fileId,
        driveId = driveId,
        keyHeader = keyHeader,
        previewThumbnail = previewThumbnail,
        // Fit-into-host (whole image) when shrunk for comments; otherwise the
        // aspect-locked crop the feed cell normally uses.
        modifier = if (fitToContent) Modifier.fillMaxSize()
        else Modifier.fillMaxWidth().aspectRatio(aspect),
        imageSize = ImageSize.THUMB_LARGE,
        // Aspect set on the modifier — let the image fill it (Crop is a no-op
        // when source aspect matches the box). When shrunk for the comments
        // band, fill the host box (Fit, whole image) instead of re-imposing the
        // image's own aspect ratio — without this the intrinsic `.aspectRatio()`
        // keeps the image at its natural ratio and it never collapses into the
        // 1/3 band (same fix the carousel and reels detail pager use).
        preserveAspectRatio = fitToContent,
        fitBounds = fitToContent,
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
        // Inline pinch-zoom for the timeline photo. No pager here (single
        // image), so no page-swipe to coordinate; at base scale taps still
        // reach the card's click handler via [onClick]/onTap.
        enableZoom = true,
    )
}

/**
 * Best-effort aspect ratio (`width / height`) from a payload's thumbnail
 * metadata. Returns `null` when no thumbnail with sane dimensions is
 * available — caller decides the fallback.
 */
/**
 * Crop cap for the single-**video** feed tile (width/height). 0.8 == a 4:5
 * portrait frame: a video at least as wide as 4:5 is sized to this tall frame
 * and center-cropped (ContentScale.Crop) so it reads as a substantial card
 * instead of a short horizontal strip; tap-to-play then shows the whole frame.
 * Taller portrait videos (ratio < 0.8) keep their natural height. Photos use
 * [MaxFeedPhotoAspect] instead (they're drawn Fit, so the cell tracks the real
 * aspect). Detail/full-screen views size media independently.
 */
internal const val MaxFeedMediaAspect = 0.8f

/**
 * Upper bound on a single-**photo** feed cell's width/height ratio.
 * 1.91 ≈ 1.91:1 (Instagram's widest landscape). The photo is drawn
 * ContentScale.Fit by the inline zoom wrapper, so the cell is sized to the
 * photo's real aspect and Fit fills it exactly — no letterbox (#873), no
 * side-crop (#818). Only panoramas wider than this clamp letterbox slightly
 * (by design, so they don't render as a sliver); tall portraits are never
 * clamped and keep their natural height.
 */
internal const val MaxFeedPhotoAspect = 1.91f

internal fun aspectRatioFor(payload: PayloadDescriptor): Float? {
    val thumb = payload.previewThumbnail ?: payload.thumbnails?.lastOrNull()
    val w = thumb?.pixelWidth
    val h = thumb?.pixelHeight
    if (w == null || h == null || w <= 0 || h <= 0) return null
    return w.toFloat() / h.toFloat()
}
