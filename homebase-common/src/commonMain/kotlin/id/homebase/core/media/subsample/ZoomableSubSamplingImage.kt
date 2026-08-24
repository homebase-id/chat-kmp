package id.homebase.core.media.subsample

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.size.Size
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.compose.zoom.ZoomableState
import com.github.panpf.zoomimage.rememberCoilZoomState
import id.homebase.core.HomebaseConstants
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.image.fullScreenImageRequest
import kotlinx.collections.immutable.persistentListOf
import org.koin.compose.koinInject

@Composable
fun ZoomableSubSamplingImage(
    source: SubSamplingImageSource,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onTap: (() -> Unit)? = null,
    // Fires with `true` once the user pinches past fit and `false` when the
    // transform returns to fit. Lets a host (e.g. a Moments carousel pager)
    // disable page-swiping while a photo is zoomed so panning a zoomed image
    // doesn't flip pages. Off by default — most callers ignore zoom state.
    onZoomedChanged: ((Boolean) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedContentStateKey: String? = null,
    /**
     * Opt-in (Vault): the photo's true aspect ratio (w/h). When supplied, the
     * shared element is a centered aspect-box drawn [ContentScale.Crop] instead
     * of the full-screen container, so a square grid tile (also Crop) uncrops
     * into the whole photo in one continuous motion on BOTH open and close.
     * Chat/Moments leave this null and keep the original container-level
     * sharedBounds path — their source bubble is already aspect-correct (Fit),
     * so they never crop/uncrop and need no hero. See [VaultZoomableImage].
     */
    heroContentAspect: Float? = null,
) {
    val coilImageLoader: ImageLoader = koinInject()
    val homebaseImageLoader: HomebaseImageLoader = koinInject()
    val generators = remember(homebaseImageLoader) {
        persistentListOf(HomebaseSubsamplingImageGenerator(homebaseImageLoader))
    }
    val zoomState = rememberCoilZoomState(subsamplingImageGenerators = generators)
    zoomState.zoomable.setKeepTransformWhenSameAspectRatioContentSizeChanged(true)

    // Preserve the user's pinch zoom when the base painter swaps size (cached
    // thumbnail placeholder -> full image) — a content-size change the library's
    // strict same-aspect-ratio check usually treats as "not a thumbnail" and
    // resets to fit.
    PreserveUserZoomAcrossContentSizeChange(zoomState.zoomable)

    if (onZoomedChanged != null) {
        ReportZoomedState(zoomState.zoomable, onZoomedChanged)
    }

    val platformContext = LocalPlatformContext.current
    val windowSize = LocalWindowInfo.current.containerSize
    val model: Any? = when (source) {
        is SubSamplingImageSource.Remote -> {
            val imageData = source.imageData
            remember(imageData, platformContext, windowSize) {
                if (windowSize.width > 0 && windowSize.height > 0) {
                    // Pin the base decode to the window size so a press-time
                    // prefetch and this request share one Coil memory-cache entry
                    // — a cold open then hits the already-decoded bitmap.
                    fullScreenImageRequest(
                        platformContext, imageData, Size(windowSize.width, windowSize.height)
                    )
                } else {
                    imageData
                }
            }
        }
        is SubSamplingImageSource.LocalFile -> source.filePath
        is SubSamplingImageSource.Url -> source.url
    }

    var imageLoaded by remember(model) { mutableStateOf(false) }

    // Cross-fade the sharp image in over the placeholder so the unavoidable
    // decode (a 6 MB JPEG can take a few hundred ms on older devices) reads as a
    // smooth focus-pull instead of an abrupt pop. (Modifier.blur is API 31+, so
    // a blur-up isn't available on older devices; a cross-fade is.) Only remote
    // images have a placeholder to fade over; a local original loads fast with
    // nothing beneath it, so it is shown at full opacity (no fade, no blank).
    val sharpAlpha by animateFloatAsState(
        targetValue = if (imageLoaded) 1f else 0f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "sharpFade",
    )
    val contentAlpha = if (source is SubSamplingImageSource.Remote) sharpAlpha else 1f

    val heroAspect = heroContentAspect?.takeIf { it.isFinite() && it > 0f }
    val hasTransition = sharedContentStateKey != null &&
        sharedTransitionScope != null && animatedVisibilityScope != null
    // Vault opts in with the photo's true aspect; chat/Moments leave it null.
    val useAspectHero = hasTransition && heroAspect != null

    // The placeholder bridge + the zoomable, drawn into whatever container the
    // chosen path supplies. [contentScale] is Crop in the Vault aspect-box (a
    // Crop box already at the photo's aspect shows the WHOLE photo) and Fit in
    // the full-screen chat container.
    val viewerLayers: @Composable (ContentScale) -> Unit = { contentScale ->
        // While the full-resolution payload downloads, show a thumbnail
        // (loadFullPayload = false) as the bridge under the cross-fade.
        // HomebaseImage paints the already-decoded list/grid tile for this image
        // synchronously on the first frame — its thumbnail cache key is
        // size-independent, so the tile the user just tapped is reused as a
        // placeholder — then sharpens to a screen-resolution server thumbnail.
        // So the bridge is the sharp tile, not the ~20px embedded blur, and there
        // is no low-res-to-sharp jump. A local file is already the original on
        // disk, so the image below loads it directly with no intermediate fetch.
        if (sharpAlpha < 1f && source is SubSamplingImageSource.Remote) {
            val imageData = source.imageData
            val thumbnailData = remember(imageData) {
                imageData.copy(loadFullPayload = false)
            }
            HomebaseImage(
                imageData = thumbnailData,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
        CoilZoomAsyncImage(
            model = model,
            contentDescription = contentDescription,
            imageLoader = coilImageLoader,
            modifier = Modifier.fillMaxSize().alpha(contentAlpha),
            contentScale = contentScale,
            zoomState = zoomState,
            scrollBar = null,
            onSuccess = { imageLoaded = true },
            onTap = onTap?.let { callback -> { _: Offset -> callback() } },
        )
    }

    // The shared element must BE the resting viewer (the bounds go on the
    // container that holds the placeholder + zoomable), so the whole viewer flies
    // as ONE piece. Putting the bounds on a separate node would leave the resting
    // copy to fade in full-size behind the flying one — a ghost / re-expand.
    //
    // Both paths emit the SAME outer-Box -> inner-Box -> viewerLayers structure
    // from a single set of call sites; only the modifiers and contentScale differ.
    // This keeps the zoomable's composition identity (the CoilZoomAsyncImage node,
    // its in-flight decode, the placeholder bridge, the imageLoaded flag, the
    // user's pinch) stable even if [useAspectHero] flips while the viewer is open
    // — e.g. a just-uploaded Vault photo whose aspect resolves only once its
    // server thumbnail dimensions arrive. A two-call-site if/else would tear the
    // zoomable subtree down on that flip (a flash + zoom reset).
    //  - Vault (hero aspect): a centered aspect-box (== the photo's Fit rect)
    //    drawn ContentScale.Crop. A Crop box already at the photo's aspect shows
    //    the WHOLE photo, and the square grid source tile is also Crop, so
    //    RemeasureToBounds uncrops the tile into the full photo in one continuous
    //    motion — symmetric on open AND close, cropped grid look preserved. The
    //    outer full-screen box keeps tap-to-toggle working on the letterbox
    //    margins (the inner aspect-box only covers the photo).
    //  - chat/Moments (no hero aspect): a full-screen container drawn
    //    ContentScale.Fit — the source bubble is already aspect-correct, so the
    //    photo simply grows (Fit -> Fit). Unchanged from before.
    val currentOnTap by rememberUpdatedState(onTap)
    val outerModifier = if (useAspectHero) {
        // Keyed on Unit so the tap detector launches once; the latest onTap is
        // read through rememberUpdatedState (callers may pass a fresh lambda each
        // recomposition, which would otherwise restart the detector mid-gesture).
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { currentOnTap?.invoke() }
            }
    } else {
        modifier.fillMaxSize()
    }
    val innerSizing = if (useAspectHero) {
        Modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.Center)
            .aspectRatio(heroAspect)
    } else {
        Modifier.fillMaxSize()
    }
    // Applied at a single call site so rememberSharedContentState stays stable
    // across a useAspectHero flip (innerSizing carries no remembered state).
    val innerModifier = innerSizing.fullScreenImageSharedBounds(
        sharedContentStateKey, sharedTransitionScope, animatedVisibilityScope,
    )
    val imageContentScale = if (useAspectHero) ContentScale.Crop else ContentScale.Fit

    Box(modifier = outerModifier) {
        Box(modifier = innerModifier) {
            viewerLayers(imageContentScale)
        }
    }
}

/**
 * Applies the full-screen image hero [SharedTransitionScope.sharedBounds] with
 * the canonical key/transform shared by the chat ([id.homebase.chat.widget])
 * and Vault destinations, so a node pairs up with the matching grid/bubble
 * source. No-op when the key or either scope is absent.
 */
@Composable
private fun Modifier.fullScreenImageSharedBounds(
    sharedContentStateKey: String?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
): Modifier {
    if (sharedContentStateKey == null || sharedTransitionScope == null || animatedVisibilityScope == null) {
        return this
    }
    return with(sharedTransitionScope) {
        this@fullScreenImageSharedBounds.sharedBounds(
            rememberSharedContentState(key = sharedContentStateKey),
            animatedVisibilityScope = animatedVisibilityScope,
            boundsTransform = { _, _ ->
                tween(
                    durationMillis = HomebaseConstants.Animation.CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION,
                    easing = FastOutSlowInEasing,
                )
            },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}

/**
 * Re-applies the user's zoom/pan after the zoomimage library resets the
 * transform on a base-painter size change (e.g. cached thumbnail → full image).
 *
 * The library keeps the transform across a content-size change only when its
 * `isThumbnailWithSize` check passes (a strict ±2px reconstruction test), so a
 * thumbnail whose aspect ratio is even slightly off the original triggers a
 * reset-to-fit and discards the user's pinch. The user's zoom is expressed
 * relative to the (re-fitted) base, so it is invariant to the content's pixel
 * size: capture it on every stable frame and, when the library resets it on a
 * size change, restore the same visible region at the same relative scale.
 */
@Composable
private fun PreserveUserZoomAcrossContentSizeChange(zoomable: ZoomableState) {
    LaunchedEffect(zoomable) {
        var prevContentSize = IntSize.Zero
        var stashedUserScale = 1f
        var stashedCenterFractionX = 0.5f
        var stashedCenterFractionY = 0.5f
        snapshotFlow {
            ContentZoomSample(
                contentSize = zoomable.contentSize,
                userScale = zoomable.userTransform.scaleX,
                baseScale = zoomable.baseTransform.scaleX,
                visibleCenterX = zoomable.contentVisibleRectF.center.x,
                visibleCenterY = zoomable.contentVisibleRectF.center.y,
            )
        }.collect { sample ->
            val size = sample.contentSize
            if (size != prevContentSize) {
                val bothNonEmpty = prevContentSize.width > 0 && prevContentSize.height > 0 &&
                    size.width > 0 && size.height > 0
                // Restore only when the user had zoomed (stashed > 1) AND the
                // library actually reset it (current ≈ 1). If the library kept
                // the transform (exact-ratio case), current is still > 1 and we
                // leave it untouched — no redundant work, no flash.
                if (bothNonEmpty &&
                    stashedUserScale > USER_SCALE_EPSILON &&
                    sample.userScale <= USER_SCALE_EPSILON &&
                    sample.baseScale > 0f
                ) {
                    zoomable.locate(
                        contentPoint = Offset(
                            x = stashedCenterFractionX * size.width,
                            y = stashedCenterFractionY * size.height,
                        ),
                        targetScale = sample.baseScale * stashedUserScale,
                        animated = false,
                    )
                }
                prevContentSize = size
            } else if (size.width > 0 && size.height > 0) {
                // Stable content size: keep the latest user zoom/pan stashed so
                // it survives the next size change.
                if (sample.userScale > USER_SCALE_EPSILON) {
                    stashedUserScale = sample.userScale
                    stashedCenterFractionX = (sample.visibleCenterX / size.width).coerceIn(0f, 1f)
                    stashedCenterFractionY = (sample.visibleCenterY / size.height).coerceIn(0f, 1f)
                } else {
                    stashedUserScale = 1f
                }
            }
        }
    }
}

private data class ContentZoomSample(
    val contentSize: IntSize,
    val userScale: Float,
    val baseScale: Float,
    val visibleCenterX: Float,
    val visibleCenterY: Float,
)

/**
 * Reports whether the user has zoomed past fit (user scale > 1). The visible
 * region is `baseTransform * userTransform`; only the user transform reflects a
 * pinch, so a base-painter size change that re-fits the image doesn't read as a
 * zoom. snapshotFlow already dedups, so [onZoomedChanged] only fires on the 1↔n
 * crossing. Reports `false` on dispose so a host re-enables paging if this page
 * scrolls off while still zoomed.
 */
@Composable
private fun ReportZoomedState(
    zoomable: ZoomableState,
    onZoomedChanged: (Boolean) -> Unit,
) {
    val latestCallback by rememberUpdatedState(onZoomedChanged)
    DisposableEffect(zoomable) {
        onDispose { latestCallback(false) }
    }
    LaunchedEffect(zoomable) {
        snapshotFlow { zoomable.userTransform.scaleX > USER_SCALE_EPSILON }
            .collect { latestCallback(it) }
    }
}

private const val USER_SCALE_EPSILON = 1.01f
