@file:OptIn(ExperimentalEncodingApi::class)

package id.homebase.core.image

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.max

/** Loading state for HomebaseImage */
sealed class HomebaseImageState {
    /** Initial state, showing preview thumbnail */
    data object Preview : HomebaseImageState()
    /** Loading higher resolution */
    data object Loading : HomebaseImageState()
    /** Successfully loaded image */
    data class Success(val bytes: ByteArray, val contentType: String) : HomebaseImageState() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || this::class != other::class) return false
            other as Success
            return bytes.contentEquals(other.bytes) && contentType == other.contentType
        }
        override fun hashCode(): Int = bytes.contentHashCode() * 31 + contentType.hashCode()
    }
    /** Failed to load */
    data class Error(val exception: Throwable?) : HomebaseImageState()
}

/**
 * Progressive image component for Homebase drives.
 *
 * Displays images with progressive loading:
 * 1. Shows embedded tinyThumb immediately (blurred)
 * 2. Loads server thumbnail at appropriate size
 * 3. Animates blur reduction as high-res loads
 * 4. Optionally loads full payload for maximum resolution
 *
 * Supports SVG, GIF, click and long-press gestures. Designed to be extensible for future zoom
 * support.
 *
 * @param imageData Image data containing file info and preview thumbnail
 * @param modifier Modifier for the image container
 * @param contentDescription Accessibility description
 * @param contentScale How to scale the image
 * @param placeholder Composable shown while loading (if no preview available)
 * @param error Composable shown on error
 * @param onClick Callback for tap gesture
 * @param onLongPress Callback for long-press with position
 * @param onStateChanged Callback when loading state changes
 * @param imageLoader Optional custom image loader instance
 */
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
        onStateChanged: ((HomebaseImageState) -> Unit)? = null,
        imageLoader: HomebaseImageLoader? = null
) {
    var imageState by remember { mutableStateOf<HomebaseImageState>(HomebaseImageState.Preview) }
    var componentSize by remember { mutableStateOf(IntSize.Zero) }

    val context = LocalPlatformContext.current

    // Animated blur radius: starts high for preview, animates to 0 for final image
    val targetBlurRadius =
            when (imageState) {
                is HomebaseImageState.Preview -> 10f
                is HomebaseImageState.Loading -> 5f
                is HomebaseImageState.Success -> 0f
                is HomebaseImageState.Error -> 0f
            }
    val blurRadius by
            animateFloatAsState(
                    targetValue = targetBlurRadius,
                    animationSpec = tween(durationMillis = 300),
                    label = "blur"
            )

    // Decode preview thumbnail for immediate display
    val previewBytes: ByteArray? =
            remember(imageData.previewThumbnail) {
                imageData.previewThumbnail?.content?.let {
                    try {
                        Base64.Default.decode(it)
                    } catch (e: Exception) {
                        null
                    }
                }
            }

    // Notify state changes
    LaunchedEffect(imageState) { onStateChanged?.invoke(imageState) }

    // Load higher resolution when size is known
    LaunchedEffect(imageData, componentSize, imageLoader) {
        if (componentSize.width <= 0 || componentSize.height <= 0) return@LaunchedEffect
        if (imageLoader == null) return@LaunchedEffect

        imageState = HomebaseImageState.Loading

        val targetSize = calculateTargetSize(componentSize)

        val result =
                if (imageData.loadFullPayload) {
                    imageLoader.loadFullPayload(imageData)
                } else {
                    imageLoader.loadThumbnail(imageData, targetSize)
                }

        imageState =
                if (result != null) {
                    HomebaseImageState.Success(result.bytes, result.contentType)
                } else {
                    HomebaseImageState.Error(null)
                }
    }

    // Gesture modifier (extensible for zoom later)
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

    Box(
            modifier = modifier.onSizeChanged { componentSize = it }.then(gestureModifier),
            contentAlignment = Alignment.Center
    ) {
        when (val state = imageState) {
            is HomebaseImageState.Preview, is HomebaseImageState.Loading -> {
                // Show preview or loading state
                if (previewBytes != null) {
                    AsyncImage(
                            model =
                                    ImageRequest.Builder(context)
                                            .data(previewBytes)
                                            .crossfade(true)
                                            .build(),
                            contentDescription = contentDescription,
                            contentScale = contentScale,
                            modifier = Modifier.fillMaxSize().blur(blurRadius.dp)
                    )
                } else {
                    placeholder?.invoke()
                }
            }
            is HomebaseImageState.Success -> {
                // Show loaded image
                val isSvg = state.contentType == "image/svg+xml"

                AsyncImage(
                        model =
                                ImageRequest.Builder(context)
                                        .data(state.bytes)
                                        .crossfade(true)
                                        .build(),
                        contentDescription = contentDescription,
                        contentScale = contentScale,
                        modifier = Modifier.fillMaxSize().blur(blurRadius.dp)
                )
            }
            is HomebaseImageState.Error -> {
                error?.invoke() ?: placeholder?.invoke()
            }
        }
    }
}

/**
 * Calculate target thumbnail size based on component dimensions. Matches one of the standard
 * thumbnail sizes.
 */
private fun calculateTargetSize(componentSize: IntSize): ImageSize {
    val maxDimension = max(componentSize.width, componentSize.height)

    // Account for display density (approximate 2x-3x)
    val targetPixels = maxDimension * 2

    return when {
        targetPixels <= 320 -> ImageSize.THUMB_SMALL
        targetPixels <= 640 -> ImageSize.THUMB_MEDIUM
        targetPixels <= 1080 -> ImageSize.THUMB_LARGE
        else -> ImageSize.THUMB_XLARGE
    }
}
