package id.homebase.core.image

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import id.homebase.core.HomebaseConstants

/** An image file on this device, keyed by its path so a rebuilt row draws Coil's in-memory copy on its first frame. */
@Composable
fun LocalFileImage(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    colorFilter: ColorFilter? = null,
) {
    val platformContext = LocalPlatformContext.current
    val request = remember(platformContext, path) {
        ImageRequest.Builder(platformContext).data(path).memoryCacheKey(path).placeholderMemoryCacheKey(path).build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        alignment = alignment,
        colorFilter = colorFilter,
    )
}

/** A media image's taps and long presses, and the bounds it shares with its full-screen viewer as that opens. */
@Composable
fun Modifier.mediaGestures(
    onClick: (() -> Unit)?,
    onLongPress: ((Offset) -> Unit)?,
    sharedKey: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
): Modifier {
    val tappable = if (onClick != null || onLongPress != null) {
        pointerInput(onClick, onLongPress) {
            detectTapGestures(onTap = { onClick?.invoke() }, onLongPress = { offset -> onLongPress?.invoke(offset) })
        }
    } else {
        this
    }
    if (sharedTransitionScope == null || animatedVisibilityScope == null) return tappable
    return with(sharedTransitionScope) {
        tappable.sharedBounds(
            rememberSharedContentState(key = sharedKey),
            animatedVisibilityScope = animatedVisibilityScope,
            boundsTransform = { _, _ ->
                tween(durationMillis = HomebaseConstants.Animation.CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION, easing = FastOutSlowInEasing)
            },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}
