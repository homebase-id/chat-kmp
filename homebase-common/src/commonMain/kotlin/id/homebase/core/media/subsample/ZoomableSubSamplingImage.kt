package id.homebase.core.media.subsample

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import coil3.ImageLoader
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.compose.zoom.ZoomableState
import com.github.panpf.zoomimage.rememberCoilZoomState
import id.homebase.core.HomebaseConstants
import id.homebase.core.image.HomebaseImage
import id.homebase.core.image.HomebaseImageLoader
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

    val model: Any? = when (source) {
        is SubSamplingImageSource.Remote -> source.imageData
        is SubSamplingImageSource.LocalFile -> source.filePath
    }

    var imageLoaded by remember(model) { mutableStateOf(false) }

    // Cross-fade the sharp image in over the placeholder so the unavoidable
    // decode (a 6 MB JPEG can take a few hundred ms on older devices) reads as a
    // smooth focus-pull instead of an abrupt pop. (Modifier.blur is API 31+, so
    // a blur-up isn't available on older devices; a cross-fade is.)
    val sharpAlpha by animateFloatAsState(
        targetValue = if (imageLoaded) 1f else 0f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "sharpFade",
    )

    // Shared-element bounds go on the container (not the inner image) so the
    // placeholder animates with the open/close transition instead of popping in
    // full-screen behind the still-animating image.
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
        // While the full-resolution payload downloads, show a thumbnail sized to
        // this fullscreen container (loadFullPayload = false) instead of letting
        // the viewer blow up a tiny list/grid thumbnail. HomebaseImage shows the
        // embedded blurred preview first and sharpens to a screen-resolution
        // server thumbnail, so the user never sees the low-res-to-sharp jump.
        // A local file is already the original on disk, so the image below loads
        // it directly with no intermediate fetch.
        if (sharpAlpha < 1f && source is SubSamplingImageSource.Remote) {
            val thumbnailData = remember(source) {
                source.imageData.copy(loadFullPayload = false)
            }
            HomebaseImage(
                imageData = thumbnailData,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        CoilZoomAsyncImage(
            model = model,
            contentDescription = contentDescription,
            imageLoader = coilImageLoader,
            modifier = Modifier.fillMaxSize().alpha(sharpAlpha),
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
