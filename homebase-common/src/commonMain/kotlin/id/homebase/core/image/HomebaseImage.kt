@file:OptIn(ExperimentalEncodingApi::class)

package id.homebase.core.image

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap

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
) {
    // Decode preview thumbnail for immediate display
    val previewBitmap =
            remember(imageData.previewThumbnail) {
                imageData.previewThumbnail?.content?.let {
                    try {
                        val bytes = Base64.Default.decode(it)
                        bytes.decodeToImageBitmap()
                    } catch (e: Exception) {
                        null
                    }
                }
            }

    // Gesture modifier
    val gestureModifier =
            if (onClick != null || onLongPress != null) {
                Modifier.pointerInput(onClick, onLongPress) {
                    detectTapGestures(
                            onTap = { onClick?.invoke() },
                            onLongPress = { offset -> onLongPress?.invoke(offset) }
                    )
                }
            } else {
                Modifier
            }

    SubcomposeAsyncImage(
            model = imageData,
            contentDescription = contentDescription,
            modifier = modifier.then(gestureModifier),
            contentScale = contentScale
    ) {
        val state = painter.state

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
                SubcomposeAsyncImageContent(modifier = Modifier.blur(blurRadius.dp))
            }
            is AsyncImagePainter.State.Error -> {
                error?.invoke() ?: placeholder?.invoke()
            }
        }
    }
}
