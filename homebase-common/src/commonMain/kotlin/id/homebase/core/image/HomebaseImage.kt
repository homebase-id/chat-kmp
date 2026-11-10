@file:OptIn(ExperimentalEncodingApi::class)

package id.homebase.core.image

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import id.homebase.core.HomebaseConstants
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.Warning
import id.homebase.resources.MR
import id.homebase.resources.error
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Progressive image component for Homebase drives.
 *
 * Displays images with progressive loading using Coil3:
 * 1. Shows embedded tinyThumb immediately (blurred)
 * 2. Loads server thumbnail/full image via Coil (with caching)
 * 3. Animates blur reduction as high-res loads
 *
 * Supports SVG, GIF, click and long-press gestures.
 *
 * @param imageData Image data containing file info and preview thumbnail
 * @param modifier Modifier for the image container
 * @param contentDescription Accessibility description
 * @param contentScale How to scale the image
 * @param placeholder Composable shown while loading (if no preview available)
 * @param error Composable shown on error
 * @param onClick Callback for tap gesture
 * @param onLongPress Callback for long-press with position
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun HomebaseImage(
    imageData: HomebaseImageData,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    placeholder: @Composable (() -> Unit)? = null,
    error: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongPress: ((Offset) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    colorFilter: ColorFilter? = null,  // ← Add this par
    onLoaded: (() -> Unit)? = null,
    stepUpToPayload: Boolean = false,
    /** Shows only the embedded preview and fetches nothing, unless the thumbnail is already in memory. */
    deferLoad: Boolean = false,
) {
    // Get ImageLoader with HomebaseImageFetcher from Koin DI
    val imageLoader: ImageLoader = koinInject()
    val koin = getKoin()

    // Decode preview thumbnail for immediate display
    val previewBitmap =
        remember(imageData.previewThumbnail) {
            imageData.previewThumbnail?.content?.let {
                try {
                    it.bytes.decodeToImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
        }

    // Paint the already-decoded thumbnail synchronously on the first frame
    // instead of the tiny embedded preview. The thumbnail cache key is
    // size-independent (see HomebaseImageKeyer), so whatever thumbnail is in
    // memory for this image — a list/grid tile, or a previously loaded
    // full-screen thumb — is shown immediately while the request resolves:
    //   - full-payload request: bridges thumb -> full (low-res to sharp),
    //   - thumbnail request (e.g. the full-screen viewer's placeholder): the
    //     placeholder key equals the request's own key, so a grid/list tile
    //     already in memory paints on frame 0 rather than the ~20px tinyThumb.
    // Pending (local, not-yet-uploaded) files have no server thumbnail to key
    // against, so they fall through to the raw data + embedded preview.
    val platformContext = LocalPlatformContext.current
    // Starts false for every image: the thumbnail shows first, so a slow payload never holds it back.
    var steppedUp by remember(imageData) { mutableStateOf(false) }
    var stepUpFailed by remember(imageData) { mutableStateOf(false) }
    var displaySize by remember(imageData) { mutableStateOf<ImageSize?>(null) }
    var lastSharpPainter by remember(imageData) { mutableStateOf<Painter?>(null) }
    val thumbnailKey = remember(imageData) { HomebaseImageKeyer.thumbnailCacheKey(imageData) }
    val model: Any = remember(imageData, platformContext, steppedUp) {
        if (!imageData.isPending) {
            ImageRequest.Builder(platformContext)
                .data(if (steppedUp) imageData.copy(loadFullPayload = true) else imageData)
                .placeholderMemoryCacheKey(thumbnailKey)
                .build()
        } else {
            imageData
        }
    }

    // Gesture modifier
    var customModified =
        if (onClick != null || onLongPress != null) {
            modifier.pointerInput(onClick, onLongPress) {
                detectTapGestures(
                    onTap = { onClick?.invoke() },
                    onLongPress = { offset -> onLongPress?.invoke(offset) }
                )
            }
        } else {
            modifier
        }

    // Shared transition modifier
    val transitionKey = "image-${imageData.fileId}-${imageData.payloadKey}"
    if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            customModified =
                customModified.sharedBounds(
                    rememberSharedContentState(key = transitionKey),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ ->
                        tween(
                            durationMillis =
                                HomebaseConstants.Animation
                                    .CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION,
                            easing = FastOutSlowInEasing
                        )
                    },
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
                )
        }
    }

    @Composable
    fun PreviewOrPlaceholder(modifier: Modifier, colorFilter: ColorFilter? = null) {
        if (previewBitmap != null) {
            Image(
                bitmap = previewBitmap,
                contentDescription = contentDescription,
                contentScale = contentScale,
                alignment = alignment,
                colorFilter = colorFilter,
                modifier = modifier,
            )
        } else {
            placeholder?.invoke()
        }
    }

    val deferred = deferLoad && !imageData.isPending &&
        remember(thumbnailKey) { imageLoader.memoryCache?.get(MemoryCache.Key(thumbnailKey)) == null }
    if (deferred) {
        PreviewOrPlaceholder(customModified.animatedBlur(remember { mutableStateOf(PREVIEW_BLUR_DP) }), colorFilter)
        return
    }

    SubcomposeAsyncImage(
        model = model,
        imageLoader = imageLoader,
        contentDescription = contentDescription,
        modifier = if (stepUpToPayload) customModified.onSizeChanged { displaySize = ImageSize(it.width, it.height) } else customModified,
        contentScale = contentScale,
        colorFilter = colorFilter
    ) {
        val state by painter.state.collectAsStateWithLifecycle()
        if (onLoaded != null && state is AsyncImagePainter.State.Success) LaunchedEffect(Unit) { onLoaded() }

        val measured = displaySize
        if (stepUpToPayload) (state as? AsyncImagePainter.State.Success)?.let { lastSharpPainter = it.painter }
        val canStepUp = stepUpToPayload && state is AsyncImagePainter.State.Success && !steppedUp && !stepUpFailed &&
            !imageData.loadFullPayload && !imageData.isPending &&
            measured != null && shouldStepUpToPayload(measured, imageData)
        if (canStepUp) {
            // The wait is what keeps a fling from downloading every payload it passes:
            // scrolling away cancels this before it fires.
            LaunchedEffect(Unit) {
                val full = imageData.copy(loadFullPayload = true)
                val loader = koin.getOrNull<HomebaseImageLoader>()
                if (loader?.hasStepUpFailed(full) == true) {
                    stepUpFailed = true
                    return@LaunchedEffect
                }
                if (loader?.isFullPayloadCached(full) != true) delay(STEP_UP_SETTLE_MILLIS)
                steppedUp = true
            }
        }
        if (steppedUp && state is AsyncImagePainter.State.Error) {
            LaunchedEffect(Unit) {
                koin.getOrNull<HomebaseImageLoader>()?.markStepUpFailed(imageData.copy(loadFullPayload = true))
                stepUpFailed = true
                steppedUp = false
            }
        }

        // Blur only the low-res embedded preview; cached thumbnails and full
        // images are already sharp so the target is 0f for those states.
        val hasSharpImage = state is AsyncImagePainter.State.Success ||
            (state is AsyncImagePainter.State.Loading &&
                (state as AsyncImagePainter.State.Loading).painter != null)

        val blurRadius = animateFloatAsState(
            targetValue = if (hasSharpImage) 0f else PREVIEW_BLUR_DP,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "blur"
        )

        // Applied to every branch so the animation transitions smoothly
        // across state changes (e.g. blurred preview → sharp full image).
        val blurMod = Modifier.animatedBlur(blurRadius)

        val failedStepUpFallback = lastSharpPainter.takeIf { steppedUp && state is AsyncImagePainter.State.Error }
        if (failedStepUpFallback != null) {
            Image(
                painter = failedStepUpFallback,
                contentDescription = contentDescription,
                contentScale = contentScale,
                alignment = alignment,
                modifier = Modifier.fillMaxSize(),
            )
            return@SubcomposeAsyncImage
        }

        when (state) {
            is AsyncImagePainter.State.Loading -> {
                val loadingPainter = (state as AsyncImagePainter.State.Loading).painter
                if (loadingPainter != null) {
                    // Cached thumbnail available via placeholderMemoryCacheKey
                    Image(
                        painter = loadingPainter,
                        contentDescription = contentDescription,
                        contentScale = contentScale,
                        alignment = alignment,
                        modifier = Modifier.fillMaxSize().then(blurMod)
                    )
                } else {
                    PreviewOrPlaceholder(Modifier.fillMaxSize().then(blurMod))
                }
            }

            is AsyncImagePainter.State.Empty -> PreviewOrPlaceholder(Modifier.fillMaxSize().then(blurMod))

            is AsyncImagePainter.State.Success -> {
                SubcomposeAsyncImageContent(
                    modifier = Modifier.fillMaxSize().then(blurMod),
                    contentScale = contentScale,
                    alignment = alignment
                )
            }

            is AsyncImagePainter.State.Error -> {
                val errorState = state as AsyncImagePainter.State.Error
                println("HomebaseImage Error: ${errorState.result.throwable.message}")

                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    PreviewOrPlaceholder(Modifier.fillMaxSize().then(blurMod))

                    // Show error overlay on top
                    if (error != null) {
                        error.invoke()
                    } else {
                        // Default error icon if no custom error composable provided
                        Icon(
                            imageVector = HomebaseIcons.Warning,
                            contentDescription = stringResource(MR.string.error),
                            tint = Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

// Same effect as Modifier.blur, but the radius is read in the draw phase so the fade doesn't recompose the image.
private fun Modifier.animatedBlur(radiusDp: State<Float>): Modifier = graphicsLayer {
    val radius = radiusDp.value
    if (radius >= 0.5f) {
        val px = radius.dp.toPx()
        renderEffect = BlurEffect(px, px, TileMode.Clamp)
        clip = true
    } else {
        renderEffect = null
        clip = false
    }
}

private const val STEP_UP_SETTLE_MILLIS = 600L
private const val PREVIEW_BLUR_DP = 10f
