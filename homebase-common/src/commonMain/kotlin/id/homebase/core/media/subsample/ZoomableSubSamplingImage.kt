package id.homebase.core.media.subsample

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.compose.zoom.ZoomableState
import com.github.panpf.zoomimage.rememberCoilZoomState
import id.homebase.core.HomebaseConstants
import id.homebase.core.image.HomebaseImageKeyer
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.image.decodeBitmap
import kotlinx.collections.immutable.persistentListOf
import org.koin.compose.koinInject

@Composable
fun ZoomableSubSamplingImage(
    source: SubSamplingImageSource,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onTap: (() -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedContentStateKey: String? = null,
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

    val platformContext = LocalPlatformContext.current
    val model: Any? = when (source) {
        is SubSamplingImageSource.Remote -> {
            val imageData = source.imageData
            remember(imageData, platformContext) {
                ImageRequest.Builder(platformContext)
                    .data(imageData)
                    .placeholderMemoryCacheKey(HomebaseImageKeyer.thumbnailCacheKey(imageData))
                    .build()
            }
        }
        is SubSamplingImageSource.LocalFile -> source.filePath
    }

    // Decode the embedded tiny thumbnail so a blurred preview shows immediately
    // while an uncached thumbnail/full image loads (cold cache). When a real
    // thumbnail is cached it renders on top via placeholderMemoryCacheKey.
    val previewBitmap: ImageBitmap? = remember(source) {
        (source as? SubSamplingImageSource.Remote)?.imageData?.previewThumbnail?.decodeBitmap()
    }
    var imageLoaded by remember(model) { mutableStateOf(false) }

    // Shared-element bounds go on the container (not the inner image) so the
    // blurred preview animates with the open/close transition instead of
    // popping in full-screen behind the still-animating image.
    var containerModifier: Modifier = modifier.fillMaxSize()
    if (sharedContentStateKey != null && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            containerModifier = containerModifier.sharedBounds(
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

    Box(modifier = containerModifier) {
        if (!imageLoaded && previewBitmap != null) {
            Image(
                bitmap = previewBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().blur(PreviewBlurRadius),
            )
        }
        CoilZoomAsyncImage(
            model = model,
            contentDescription = contentDescription,
            imageLoader = coilImageLoader,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            zoomState = zoomState,
            scrollBar = null,
            onSuccess = { imageLoaded = true },
            onTap = onTap?.let { callback -> { _: Offset -> callback() } },
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

private const val USER_SCALE_EPSILON = 1.01f
private val PreviewBlurRadius = 12.dp
