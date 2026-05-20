package id.homebase.core.media

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun rememberZoomState(
    minScale: Float = 1f,
    maxScale: Float = 5f,
): ZoomState {
    return remember(minScale, maxScale) { ZoomState(minScale, maxScale) }
}

@Composable
fun ZoomableContainer(
    modifier: Modifier = Modifier,
    state: ZoomState = rememberZoomState(),
    onTap: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val currentOnTap by rememberUpdatedState(onTap)

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()

        val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
            state.applyTransform(
                scaleFactor = zoomChange,
                offsetDelta = offsetChange,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = state.scale,
                    scaleY = state.scale,
                    translationX = state.offset.x,
                    translationY = state.offset.y,
                )
                .transformable(
                    state = transformState,
                    canPan = { panDelta ->
                        if (!state.isZoomed) return@transformable false
                        val maxX = (viewportWidth * state.scale - viewportWidth) / 2f
                        val atBound = when {
                            panDelta.x > 0 -> state.offset.x >= maxX - EDGE_TOLERANCE
                            panDelta.x < 0 -> state.offset.x <= -maxX + EDGE_TOLERANCE
                            else -> false
                        }
                        !atBound
                    },
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { state.toggleDoubleTapZoom() },
                        onTap = { currentOnTap?.invoke() },
                    )
                },
        ) {
            content()
        }
    }
}

private const val EDGE_TOLERANCE = 0.5f
