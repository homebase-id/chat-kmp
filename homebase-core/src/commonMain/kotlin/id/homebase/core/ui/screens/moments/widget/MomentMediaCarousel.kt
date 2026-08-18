package id.homebase.core.ui.screens.moments.widget

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
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
 * Aspect ratio: every page shares ONE frame (per-page heights would make the
 * pager jump on every swipe), sized by [momentFrameAspect] from the **tallest**
 * page and bounded by the viewport height budget — see [MomentMediaFrame]. That
 * keeps the frame tall enough for every page, so nothing is cropped and a
 * landscape first item can no longer crush later portrait pages into a short
 * strip (#873). It replaces the fixed 4:5 box, which letterboxed the (very
 * common) 2:3 and 3:4 phone portraits on both sides (#1128). Pages narrower or
 * wider than the shared frame are drawn Fit inside it.
 *
 * Video pages take part in that frame on the same terms as photos — the single-video
 * crop cap ([MaxFeedMediaAspect]) deliberately does NOT apply here. Capping a wide
 * video page to 4:5 would drag the shared frame tall enough to letterbox every wide
 * photo page beside it, trading one medium's framing for the other's blank bars.
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
    // True when the host moment is the most-centred video card in the feed.
    // Drives Instagram-style autoplay: the current page autoplays if it is
    // a video, pauses on page-change to a non-video, and resumes if the
    // user swipes back. Clears when the parent says the card is no longer
    // active (scroll off → autoplay disengages → LazyColumn eventually
    // disposes the tile and tears the player down).
    autoplayActive: Boolean = false,
    // Fires with the payload key of whichever page is currently centred so
    // the host can pass it to the tap-into-detail navigation — opening the
    // detail at the page the user was looking at, not always page 0.
    onVisiblePayloadChanged: (String) -> Unit = {},
    // Force the whole video frame to show (fit) instead of crop-to-fill —
    // set while the host card is shrunk for the comments sheet.
    fitToContent: Boolean = false,
    // Height budget for the shared frame. Defaults to the viewport rule;
    // injected explicitly by the layout tests.
    maxMediaHeight: Dp = momentMediaMaxHeight(),
) {
    if (payloads.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { payloads.size })
    // One shared frame for every page, tall enough for the tallest of them.
    // Taking aspectRatioFor(payloads[0]) here would let a landscape first item
    // determine every page's frame, which crushed the rest of the carousel.
    val aspect = remember(payloads) { momentFrameAspect(payloads) }

    // Only one tile in the carousel can be in the playing state at a time.
    // Keyed by payload key — cleared when the user swipes to a different page
    // so the previous video pauses without the user having to tap again.
    var playingPayloadKey by remember(messageId) { mutableStateOf<String?>(null) }

    // Set while the centred photo is pinched past fit. Disables page-swiping
    // so panning a zoomed photo doesn't flip carousel pages; clears (and paging
    // resumes) when the photo returns to fit or the page scrolls off.
    var zoomedPageActive by remember(messageId) { mutableStateOf(false) }

    // Two modes share this effect:
    //   - autoplayActive=true (card is the visible / active card): autoplay
    //     the current page if it's a video; clear on swipe-to-non-video.
    //   - autoplayActive=false (card is in the feed but not the active one):
    //     don't drive playback automatically, but DO clear playingPayloadKey
    //     whenever the user manually swipes pages, so a manually-played
    //     video on page N pauses when page N+1 scrolls in.
    // snapshotFlow dedups via structural equality, so emissions only land
    // when the active key actually changes — no per-frame churn on scroll.
    LaunchedEffect(pagerState, payloads, messageId) {
        snapshotFlow { pagerState.currentPage }.collect { idx ->
            payloads.getOrNull(idx)?.key?.let(onVisiblePayloadChanged)
        }
    }

    LaunchedEffect(pagerState, payloads, autoplayActive, messageId) {
        Logger.d(tag = "MomentVideo") {
            "carousel autoplay watcher: messageId=$messageId autoplayActive=$autoplayActive payloadCount=${payloads.size}"
        }
        if (!autoplayActive) {
            snapshotFlow { pagerState.currentPage }.collect { _ ->
                playingPayloadKey = null
            }
            return@LaunchedEffect
        }
        snapshotFlow {
            val payload = payloads.getOrNull(pagerState.currentPage)
                ?: return@snapshotFlow null
            val ct = payload.contentType ?: ""
            val isVideo = ct.startsWith("video/") || ct == "application/vnd.apple.mpegurl"
            if (isVideo) payload.key else null
        }.collect { autoplayKey ->
            Logger.d(tag = "MomentVideo") {
                "carousel autoplay engage: messageId=$messageId page=${pagerState.currentPage} key=${autoplayKey ?: "<no-video>"}"
            }
            playingPayloadKey = autoplayKey
        }
    }

    val pages: @Composable BoxScope.() -> Unit = {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // Keep neighbours in composition so encrypted-image fetch + video
            // descriptor prep finishes before the user lands on the page —
            // arrival should feel instant, not "blank tile that loads in."
            beyondViewportPageCount = 1,
            // Suspend page-swiping while a photo is zoomed so panning the
            // zoomed image doesn't flip pages; resumes at fit.
            userScrollEnabled = !zoomedPageActive,
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
                    fitToContent = fitToContent,
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
                    // Photo pages always render Fit — the zoom wrapper below draws
                    // Fit regardless of this flag. The shared frame is sized to the
                    // tallest page, so that page fills it exactly and the others are
                    // shown whole inside it; nothing is cropped either way.
                    preserveAspectRatio = fitToContent,
                    fitBounds = fitToContent,
                    shape = RectangleShape,
                    onClick = onMediaClick?.let { handler -> { handler(payload) } },
                    onLongPress = onMediaLongPress?.let { handler -> { offset -> handler(payload, offset) } },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    isDownloading = downloadingFiles.contains("${messageId}_${payload.key}"),
                    messageId = messageId,
                    isUploading = isUploading,
                    // Inline pinch-zoom; while shrunk for comments the box is a
                    // short band, so keep the lightweight fit render there.
                    enableZoom = !fitToContent,
                    // Only the centred page may gate the pager — a neighbour
                    // (kept composed by beyondViewportPageCount) can't be
                    // pinched, but ignore its reports defensively.
                    onZoomedChanged = { zoomed ->
                        if (pagerState.currentPage == pageIndex) {
                            zoomedPageActive = zoomed
                        }
                    },
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

    // When shrunk for the comments sheet the host gives us an explicit (1/3)
    // height — fill it instead of re-imposing the shared frame, which would
    // otherwise keep the carousel at its full width/aspect height and ignore the
    // band (the single-image and video paths already honor this).
    if (fitToContent) {
        Box(modifier = modifier.fillMaxSize(), content = pages)
    } else {
        MomentMediaFrame(
            aspect = aspect,
            maxHeight = maxMediaHeight,
            modifier = modifier,
            content = pages,
        )
    }
}
