package id.homebase.core.image

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import id.homebase.api.serialization.Base64Bytes
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.Warning
import id.homebase.resources.MR
import id.homebase.resources.error
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin
import org.koin.compose.koinInject

/**
 * Progressive image component for Homebase drives.
 *
 * Displays images with progressive loading using Coil3:
 * 1. Shows the embedded tinyThumb, decoded by Coil off the main thread and kept in its memory cache
 * 2. Loads server thumbnail/full image via Coil (with caching)
 * 3. Crossfades from the tiny to the sharp image once it loads
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
    /** False only for a page a pager keeps composed off screen. */
    stepUpToPayload: Boolean = true,
    /** Shows only the embedded preview and fetches nothing, unless the thumbnail is already in memory. */
    deferLoad: Boolean = false,
    /** The sender's own file, drawn sharp instead of the preview while the sent copy loads and if it can't. */
    standIn: String? = null,
) {
    // Get ImageLoader with HomebaseImageFetcher from Koin DI
    val imageLoader: ImageLoader = koinInject()
    val koin = getKoin()
    val preview = rememberPreview(imageData.previewThumbnail?.content, imageLoader)

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
    val displaySize = remember(imageData) { mutableStateOf<ImageSize?>(null) }
    val mayStepUp = stepUpToPayload && imageData.mayStepUp
    val thumbnailKey = remember(imageData) { HomebaseImageKeyer.thumbnailCacheKey(imageData) }
    // Not over a placeholder or stand-in, which go at once and would flash blank; fixed per image so dropping the stand-in doesn't restart the load.
    val crossfade = remember(imageData) { preview?.image != null && standIn == null }
    val model = remember(imageData, platformContext, steppedUp, crossfade) {
        ImageRequest.Builder(platformContext)
            .data(if (steppedUp) imageData.copy(loadFullPayload = true) else imageData)
            .apply { if (!imageData.isPending) placeholderMemoryCacheKey(thumbnailKey) }
            .crossfade(crossfade)
            .build()
    }
    // Composition reads it only through the two flags below, so a load moving on doesn't recompose the image.
    val loadState = remember(imageData) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    val lastSharpPainter = remember(imageData) { mutableStateOf<Painter?>(null) }
    val drawsImage = remember(loadState, preview) {
        derivedStateOf { loadState.value.painter.let { it != null && it !== preview } }
    }
    // A failure with a sharp image still on screen, such as a failed step-up, shows no error.
    val failed = remember(loadState, drawsImage) {
        derivedStateOf { loadState.value is AsyncImagePainter.State.Error && !drawsImage.value }
    }

    // A stand-in that can't be read (a swept temp file, a lapsed picker grant) gives way to the preview and error icon.
    var standInFailed by remember(standIn) { mutableStateOf(false) }
    val showsStandIn = standIn != null && !standInFailed

    val full = remember(imageData) { imageData.copy(loadFullPayload = true) }
    if (mayStepUp && !steppedUp && !stepUpFailed) {
        LaunchedEffect(imageData, contentScale) {
            snapshotFlow {
                loadState.value is AsyncImagePainter.State.Success &&
                    displaySize.value?.let { shouldStepUpToPayload(it, imageData, crop = contentScale.crops()) } == true
            }.first { it }
            val loader = koin.getOrNull<HomebaseImageLoader>()
            if (loader?.hasStepUpFailed(full) == true) {
                stepUpFailed = true
                return@LaunchedEffect
            }
            // The wait is what keeps a fling from downloading every payload it passes:
            // scrolling away cancels this before it fires.
            if (loader?.isFullPayloadCached(full) != true) delay(STEP_UP_SETTLE_MILLIS)
            steppedUp = true
        }
    }
    // Outside the step-up gate, so a pager page that stops stepping up mid-download still records the failure.
    if (steppedUp) {
        LaunchedEffect(full) {
            snapshotFlow { (loadState.value as? AsyncImagePainter.State.Error)?.result?.request?.data == full }.first { it }
            koin.getOrNull<HomebaseImageLoader>()?.markStepUpFailed(full)
            stepUpFailed = true
            steppedUp = false
        }
    }

    val deferred = deferLoad && !imageData.isPending &&
        remember(thumbnailKey) { imageLoader.memoryCache?.get(MemoryCache.Key(thumbnailKey)) == null }
    // The stand-in is drawn under the image, so the tiny on top would hide it.
    SideEffect { preview?.hidden = showsStandIn && !deferred }

    val boxModifier = modifier.mediaGestures(
        onClick, onLongPress, mediaSharedKey(imageData.fileId, imageData.payloadKey), sharedTransitionScope, animatedVisibilityScope,
    ).let { if (mayStepUp) it.onSizeChanged { size -> displaySize.value = ImageSize(size.width, size.height) } else it }

    Box(boxModifier, contentAlignment = alignment, propagateMinConstraints = true) {
        // Under the image: Coil may already draw a photo from memory on this first frame.
        if (placeholder != null && (deferred || !showsStandIn) && preview?.image == null && !drawsImage.value) placeholder()
        if (deferred) {
            preview?.let { Image(it, contentDescription, Modifier.fillMaxSize(), alignment, contentScale, colorFilter = colorFilter) }
        } else {
            if (standIn != null && !standInFailed && !drawsImage.value) {
                // The image on top carries the description.
                LocalFileImage(standIn, null, Modifier.fillMaxSize(), contentScale, alignment, colorFilter, onError = { standInFailed = true })
            }
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                imageLoader = imageLoader,
                modifier = Modifier.fillMaxSize(),
                // A reload or failed step-up keeps the sharp image already shown instead of dropping back to the tiny.
                transform = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Loading -> if (state.painter == null) state.copy(painter = lastSharpPainter.value ?: preview) else state
                        is AsyncImagePainter.State.Error -> state.copy(painter = lastSharpPainter.value ?: preview)
                        else -> state
                    }
                },
                onState = { state ->
                    loadState.value = state
                    // Includes a thumbnail Coil drew from memory while loading, so a failure keeps it.
                    if (state.painter.let { it != null && it !== preview }) lastSharpPainter.value = state.painter
                    if (state is AsyncImagePainter.State.Success) onLoaded?.invoke()
                    if (state is AsyncImagePainter.State.Error && !steppedUp) {
                        Logger.w(tag = "HomebaseImage") { "Image failed to load: ${state.result.throwable.message}" }
                    }
                },
                alignment = alignment,
                contentScale = contentScale,
                colorFilter = colorFilter,
            )
        }
        if (!showsStandIn && failed.value) {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                if (error != null) {
                    error.invoke()
                } else {
                    // Default error icon if no custom error composable provided
                    Icon(
                        imageVector = HomebaseIcons.Warning,
                        contentDescription = stringResource(MR.string.error),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

/** The tiny from Coil's memory cache, where [TinyPreviewWarmer] decoded it off the main thread. */
@Composable
private fun rememberPreview(bytes: Base64Bytes?, imageLoader: ImageLoader): PreviewPainter? {
    if (bytes == null) return null
    val context = LocalPlatformContext.current
    return remember(bytes, context, imageLoader) {
        val request = previewRequest(context, bytes)
        val warmed = imageLoader.cachedPreview(request)?.asPainter(context)
        PreviewPainter(warmed ?: decodeUnwarmed(bytes, request, imageLoader))
    }
}

internal var tinyPreviewMisses = 0
    private set

// A view the warm-up didn't reach still draws its tiny on the first frame; the miss is logged, and Coil caches it for next time.
private fun decodeUnwarmed(bytes: Base64Bytes, request: ImageRequest, imageLoader: ImageLoader): Painter? {
    tinyPreviewMisses++
    if (tinyPreviewMisses == 1 || tinyPreviewMisses % 100 == 0) {
        Logger.i(tag = "HomebaseImage") { "Tiny preview not warmed, decoded on the main thread (miss $tinyPreviewMisses)" }
    }
    imageLoader.enqueue(request)
    return bytes.decodeImageBitmap()?.let(::BitmapPainter)
}

// Not a RememberObserver, so Coil drawing it as a placeholder never restarts the tiny's load.
private class PreviewPainter(val image: Painter?) : Painter() {
    var hidden: Boolean by mutableStateOf(false)
    private var alpha = DefaultAlpha
    private var colorFilter: ColorFilter? = null

    override val intrinsicSize: Size get() = image?.intrinsicSize ?: Size.Unspecified

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        this.colorFilter = colorFilter
        return true
    }

    override fun DrawScope.onDraw() {
        if (!hidden) image?.run { draw(size, alpha, colorFilter) }
    }
}

private const val STEP_UP_SETTLE_MILLIS = 600L

// Coil's own mapping: everything but Fit and Inside fills the view, so the fetcher sees it as a crop too.
internal fun ContentScale.crops(): Boolean = this != ContentScale.Fit && this != ContentScale.Inside

