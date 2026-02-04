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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import id.homebase.core.HomebaseConstants
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.core.ui.assets.Warning
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.koin.compose.koinInject
import kotlin.io.encoding.Base64
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
    placeholder: @Composable (() -> Unit)? = null,
    error: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongPress: ((Offset) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    // Get ImageLoader with HomebaseImageFetcher from Koin DI
    val imageLoader: ImageLoader = koinInject()

    // Decode preview thumbnail for immediate display
    val previewBitmap =
        remember(imageData.previewThumbnail) {
            imageData.previewThumbnail?.content?.let {
                try {
                    val bytes = Base64.decode(it)
                    bytes.decodeToImageBitmap()
                } catch (_: Exception) {
                    null
                }
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
    Logger.d("HomebaseImage: transitionKey = $transitionKey")
    if (sharedTransitionScope != null) {
        with(sharedTransitionScope) {
            customModified = customModified.sharedBounds(
                rememberSharedContentState(key = transitionKey),
                animatedVisibilityScope = animatedVisibilityScope,
                boundsTransform = { _, _ ->
                    tween(durationMillis = HomebaseConstants.Animation.CHAT_IMAGE_FULL_SCREEN_TRANSITION_DURATION, easing = FastOutSlowInEasing)
                },
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
            )
        }
    }

    SubcomposeAsyncImage(
        model = imageData,
        imageLoader = imageLoader,
        contentDescription = contentDescription,
        modifier = customModified,
        contentScale = contentScale
    ) {
        val state by painter.state.collectAsState()

        // Animate blur: 10f -> 0f on success
        val blurRadius by
        animateFloatAsState(
            targetValue = if (state is AsyncImagePainter.State.Success) 0f else 10f,
            animationSpec = tween(durationMillis = 300),
            label = "blur"
        )

        when (state) {
            is AsyncImagePainter.State.Loading, is AsyncImagePainter.State.Empty -> {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap,
                        contentDescription = contentDescription,
                        contentScale = contentScale,
                        modifier = Modifier.fillMaxSize().blur(blurRadius.dp)
                    )
                } else {
                    placeholder?.invoke()
                }
            }

            is AsyncImagePainter.State.Success -> {
                SubcomposeAsyncImageContent(
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale
                )
            }

            is AsyncImagePainter.State.Error -> {
                val errorState = state as AsyncImagePainter.State.Error
                println("HomebaseImage Error: ${errorState.result.throwable.message}")

                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap,
                            contentDescription = contentDescription,
                            contentScale = contentScale,
                            modifier = Modifier.fillMaxSize().blur(blurRadius.dp)
                        )
                    } else {
                        placeholder?.invoke()
                    }

                    // Show error overlay on top
                    if (error != null) {
                        error.invoke()
                    } else {
                        // Default error icon if no custom error composable provided
                        Icon(
                            imageVector = HomebaseIcons.Warning,
                            contentDescription = "Error",
                            tint = Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
