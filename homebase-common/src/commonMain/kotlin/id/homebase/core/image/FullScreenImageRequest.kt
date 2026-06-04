package id.homebase.core.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size
import org.koin.compose.koinInject

/**
 * Canonical Coil request for the fullscreen base image.
 *
 * The decode size is pinned to [size] (the window) rather than left to the
 * measured composable, so a press-time prefetch and the fullscreen viewer
 * produce the SAME Coil memory-cache key. That lets a cold open hit an
 * already-decoded bitmap instead of decoding the 6 MB JPEG again.
 *
 * Deep zoom is unaffected: the subsampling generator streams full-resolution
 * tiles from the payload bytes independently of this base size.
 */
fun fullScreenImageRequest(
    context: PlatformContext,
    data: HomebaseImageData,
    size: Size,
): ImageRequest =
    ImageRequest.Builder(context)
        .data(data)
        .size(size)
        .build()

/**
 * Decode and cache the fullscreen bitmap (and, via the fetcher, the byte cache)
 * so the viewer opens instantly. Fire-and-forget Coil preload — it has no
 * target, so it survives the press composable leaving the screen. A press that
 * does not lead to an open wastes at most one image's decode.
 */
fun ImageLoader.prefetchFullScreen(
    context: PlatformContext,
    data: HomebaseImageData,
    size: Size,
) {
    if (data.isPending) return
    enqueue(fullScreenImageRequest(context, data, size))
}

/**
 * Returns a press handler that warms an image's fullscreen bitmap via
 * [prefetchFullScreen]. Encapsulates the Coil loader, platform context, and
 * window size so press sites (grid tiles, chat photos) share one implementation
 * instead of each re-reading them. The returned lambda is a no-op until the
 * window has been measured.
 */
@Composable
fun rememberFullScreenImagePrefetch(): (HomebaseImageData) -> Unit {
    val imageLoader: ImageLoader = koinInject()
    val context = LocalPlatformContext.current
    val windowSize = LocalWindowInfo.current.containerSize
    return remember(imageLoader, context, windowSize) {
        { data ->
            if (windowSize.width > 0 && windowSize.height > 0) {
                imageLoader.prefetchFullScreen(
                    context, data, Size(windowSize.width, windowSize.height)
                )
            }
        }
    }
}
