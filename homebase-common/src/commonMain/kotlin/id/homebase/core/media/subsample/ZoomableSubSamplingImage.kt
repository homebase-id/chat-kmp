package id.homebase.core.media.subsample

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.core.image.decodeBitmap
import id.homebase.core.media.ZoomState
import id.homebase.core.media.ZoomableContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

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
) {
    val scope = rememberCoroutineScope()
    val zoomState = remember { ZoomState() }
    var tileManager by remember { mutableStateOf<TileManager?>(null) }

    val previewBitmap = remember(previewThumbnail) {
        previewThumbnail?.decodeBitmap()
    }

    LaunchedEffect(source) {
        val bytes = withContext(Dispatchers.Default) { source.loadBytes() }
            ?: return@LaunchedEffect
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
                val viewport = calculateViewport(
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

    val hasLoadedTiles = currentManager != null
    val blurRadius by animateFloatAsState(
        targetValue = if (hasLoadedTiles) 0f else 10f,
        animationSpec = tween(durationMillis = 300),
        label = "blur",
    )
    val blurMod = if (blurRadius >= 0.5f) Modifier.blur(blurRadius.dp) else Modifier

    ZoomableContainer(state = zoomState, onTap = onTap, modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (previewBitmap != null && currentManager == null) {
                Image(
                    bitmap = previewBitmap,
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
            }
        }
    }
}

private fun calculateViewport(
    scale: Float,
    offset: Offset,
    imageSize: IntSize,
): IntRect {
    val viewportWidth = (imageSize.width / scale).toInt()
    val viewportHeight = (imageSize.height / scale).toInt()
    val centerX = imageSize.width / 2 - (offset.x / scale).toInt()
    val centerY = imageSize.height / 2 - (offset.y / scale).toInt()
    return IntRect(
        left = (centerX - viewportWidth / 2).coerceAtLeast(0),
        top = (centerY - viewportHeight / 2).coerceAtLeast(0),
        right = (centerX + viewportWidth / 2).coerceAtMost(imageSize.width),
        bottom = (centerY + viewportHeight / 2).coerceAtMost(imageSize.height),
    )
}
