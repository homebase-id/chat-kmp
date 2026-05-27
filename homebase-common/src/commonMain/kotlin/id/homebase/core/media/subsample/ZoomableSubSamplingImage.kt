package id.homebase.core.media.subsample

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.core.HomebaseConstants
import id.homebase.core.image.decodeBitmap
import id.homebase.core.media.ZoomState
import id.homebase.core.media.ZoomableContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

@OptIn(FlowPreview::class)
@Composable
fun ZoomableSubSamplingImage(
    source: SubSamplingImageSource,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    previewThumbnail: EmbeddedThumb? = null,
    onTap: (() -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    sharedContentStateKey: String? = null,
) {
    val scope = rememberCoroutineScope()
    val zoomState = remember { ZoomState() }
    var tileManager by remember { mutableStateOf<TileManager?>(null) }
    var fallbackState by remember { mutableStateOf<TileState?>(null) }

    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(previewThumbnail) {
        previewBitmap = previewThumbnail?.let {
            withContext(Dispatchers.Default) { it.decodeBitmap() }
        }
    }

    LaunchedEffect(source) {
        tileManager?.close()
        tileManager = null
        fallbackState = null

        val bytes = withContext(Dispatchers.Default) { source.loadBytes() }
            ?: return@LaunchedEffect

        if (!isRegionDecodable(bytes)) {
            val fullBitmap = withContext(Dispatchers.Default) {
                try { bytes.decodeToImageBitmap() } catch (_: Exception) { null }
            }
            if (fullBitmap != null) {
                fallbackState = TileState(
                    baseLayer = fullBitmap,
                    imageSize = IntSize(fullBitmap.width, fullBitmap.height),
                )
            }
            return@LaunchedEffect
        }

        val decoder = withContext(Dispatchers.Default) {
            try {
                createImageRegionDecoder(bytes)
            } catch (_: Exception) {
                null
            }
        } ?: return@LaunchedEffect
        val manager = TileManager(decoder, scope = scope)
        manager.loadBaseLayer()
        tileManager = manager
    }

    val currentManager = tileManager
    LaunchedEffect(currentManager) {
        if (currentManager == null) return@LaunchedEffect
        snapshotFlow { Triple(zoomState.scale, zoomState.offset, currentManager.state.value.imageSize) }
            .debounce(100)
            .collectLatest { (scale, offset, imageSize) ->
                if (imageSize.width == 0) return@collectLatest
                val viewport = TileGrid.calculateViewport(
                    scale = scale,
                    offset = offset,
                    imageSize = imageSize,
                )
                currentManager.onViewportChanged(viewport, scale)
            }
    }

    DisposableEffect(Unit) {
        onDispose { tileManager?.close() }
    }

    val hasLoadedImage = currentManager != null || fallbackState != null
    val blurRadius by animateFloatAsState(
        targetValue = if (hasLoadedImage) 0f else 10f,
        animationSpec = tween(durationMillis = 300),
        label = "blur",
    )
    val blurMod = if (blurRadius >= 0.5f) Modifier.blur(blurRadius.dp) else Modifier

    var sharedModifier = modifier
    if (sharedContentStateKey != null && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            sharedModifier = sharedModifier.sharedBounds(
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

    ZoomableContainer(state = zoomState, onTap = onTap, modifier = sharedModifier) {
        Box(modifier = Modifier.fillMaxSize()) {
            val preview = previewBitmap
            if (preview != null && !hasLoadedImage) {
                Image(
                    bitmap = preview,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().then(blurMod),
                )
            }

            if (currentManager != null) {
                val tileState by currentManager.state.collectAsStateWithLifecycle()
                SubSamplingImage(
                    tileState = tileState,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (fallbackState != null) {
                SubSamplingImage(
                    tileState = fallbackState!!,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun isRegionDecodable(bytes: ByteArray): Boolean {
    if (bytes.size < 4) return false
    // GIF: starts with "GIF" (0x47 0x49 0x46)
    if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) return false
    // SVG: starts with '<' (potentially "<?xml" or "<svg")
    if (bytes[0] == 0x3C.toByte()) return false
    return true
}
