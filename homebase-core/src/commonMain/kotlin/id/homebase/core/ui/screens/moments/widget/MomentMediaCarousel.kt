package id.homebase.core.ui.screens.moments.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.core.image.ImageSize
import kotlin.uuid.Uuid

private val DotSize = 6.dp
private val DotSpacing = 4.dp

/**
 * Instagram-style horizontal swipe carousel for moments whose payload set has
 * more than one media item. Used by [MomentMediaGallery] in the feed when
 * `payloads.size > 1`. Single-payload moments keep the existing aspect-fitted
 * single-cell rendering (see `SingleImageLayout` / `MomentInlineVideoTile`).
 *
 * Aspect ratio: locked to the first payload's thumbnail ratio (falls back to
 * 1:1 when no metadata is available). Mirrors Instagram — every page in a
 * carousel post shares the first item's aspect; later items crop to fit.
 *
 * Videos: the page renders [MomentInlineVideoTile] in place, so the user can
 * play any video without leaving the feed. Only one video plays at a time per
 * moment — swiping to a different page resets the internal playing-key state,
 * pausing whatever was active. (Cross-card pause-on-scroll-out is handled by
 * LazyColumn disposing off-screen items, which tears down the player.)
 */
@Composable
fun MomentMediaCarousel(
    payloads: List<PayloadDescriptor>,
    fileId: Uuid,
    driveId: Uuid,
    previewThumbnail: EmbeddedThumb?,
    keyHeader: KeyHeader,
    messageId: Uuid,
    downloadingFiles: Set<String>,
    isUploading: Boolean,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onMediaClick: ((PayloadDescriptor) -> Unit)?,
    onMediaLongPress: ((PayloadDescriptor, Offset) -> Unit)?,
    onDoubleTap: () -> Unit,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    modifier: Modifier = Modifier,
) {
    if (payloads.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { payloads.size })
    val aspect = aspectRatioFor(payloads[0]) ?: 1f

    // Only one tile in the carousel can be in the playing state at a time.
    // Keyed by payload key — cleared when the user swipes to a different page
    // so the previous video pauses without the user having to tap again.
    var playingPayloadKey by remember(messageId) { mutableStateOf<String?>(null) }

    LaunchedEffect(pagerState, messageId) {
        snapshotFlow { pagerState.currentPage }.collect { _ ->
            playingPayloadKey = null
        }
    }

    Box(modifier = modifier.fillMaxWidth().aspectRatio(aspect)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // Keep neighbours in composition so encrypted-image fetch + video
            // descriptor prep finishes before the user lands on the page —
            // arrival should feel instant, not "blank tile that loads in."
            beyondViewportPageCount = 1,
            // Match the visual pattern of the existing single-cell renderers
            // (no inter-page gap on the moment card). If we want IG-style
            // 4dp "peek" gaps, add `pageSpacing = 4.dp` here.
        ) { pageIndex ->
            val payload = payloads[pageIndex]
            val contentType = payload.contentType ?: ""
            val isVideo = contentType.startsWith("video/") ||
                contentType == "application/vnd.apple.mpegurl"

            if (isVideo) {
                MomentInlineVideoTile(
                    payload = payload,
                    fileId = fileId,
                    driveId = driveId,
                    keyHeader = keyHeader,
                    previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb()
                        ?: previewThumbnail,
                    isUploading = isUploading,
                    isPlaying = playingPayloadKey == payload.key,
                    onPlayTap = {
                        playingPayloadKey = if (playingPayloadKey == payload.key) {
                            null
                        } else {
                            payload.key
                        }
                    },
                    onDoubleTap = onDoubleTap,
                    isMuted = isMuted,
                    onToggleMute = onToggleMute,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    modifier = Modifier.fillMaxSize(),
                    // Restrict play/pause to the centred IconButton and
                    // suppress native player controls so horizontal swipes
                    // reach the carousel pager cleanly.
                    tapMode = MomentVideoTapMode.ButtonOnly,
                )
            } else {
                MomentMediaItem(
                    payload = payload,
                    fileId = fileId,
                    driveId = driveId,
                    keyHeader = keyHeader,
                    previewThumbnail = payload.previewThumbnail?.toEmbeddedThumb()
                        ?: previewThumbnail,
                    modifier = Modifier.fillMaxSize(),
                    imageSize = ImageSize.THUMB_LARGE,
                    // The carousel box is the aspect-locked region; let images
                    // crop to it the same way the existing 4-up grid does.
                    preserveAspectRatio = false,
                    shape = RectangleShape,
                    onClick = onMediaClick?.let { handler -> { handler(payload) } },
                    onLongPress = onMediaLongPress?.let { handler -> { offset -> handler(payload, offset) } },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    isDownloading = downloadingFiles.contains("${messageId}_${payload.key}"),
                    messageId = messageId,
                    isUploading = isUploading,
                )
            }
        }

        // Dot indicators — translucent capsule overlay at bottom-center, like
        // Instagram. Drawn over the media so the dots track the same vertical
        // rhythm whether the page is portrait, landscape, or square.
        if (payloads.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(DotSpacing),
            ) {
                repeat(payloads.size) { i ->
                    val active = i == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .size(DotSize)
                            .clip(CircleShape)
                            .background(
                                if (active) Color.White
                                else Color.White.copy(alpha = 0.45f),
                            ),
                    )
                }
            }
        }
    }
}
