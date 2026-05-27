package id.homebase.core.media.subsample

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.rememberCoilZoomState
import id.homebase.core.HomebaseConstants
import id.homebase.core.image.HomebaseImageKeyer
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
    val homebaseImageLoader: HomebaseImageLoader = koinInject()
    val generators = remember(homebaseImageLoader) {
        persistentListOf(HomebaseSubsamplingImageGenerator(homebaseImageLoader))
    }
    val zoomState = rememberCoilZoomState(subsamplingImageGenerators = generators)

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

    var imageModifier = modifier.fillMaxSize()
    if (sharedContentStateKey != null && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            imageModifier = imageModifier.sharedBounds(
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

    CoilZoomAsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = imageModifier,
        contentScale = ContentScale.Fit,
        zoomState = zoomState,
        onTap = onTap?.let { callback -> { _: Offset -> callback() } },
    )
}
