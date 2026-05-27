package id.homebase.core.media

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

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
    doubleClickListener: DoubleClickToZoomListener = DoubleClickToZoomListener.cycle(),
    hardwareShortcutDetector: HardwareShortcutDetector = HardwareShortcutDetector.Default,
    content: @Composable () -> Unit,
) {
    val currentOnTap by rememberUpdatedState(onTap)
    val scope = rememberCoroutineScope()

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
                    canPan = { state.isZoomed },
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            scope.launch {
                                doubleClickListener.onDoubleClick(
                                    state,
                                    offset,
                                    viewportWidth,
                                    viewportHeight,
                                )
                            }
                        },
                        onTap = { currentOnTap?.invoke() },
                    )
                }
                .onKeyEvent { event ->
                    val shortcut = hardwareShortcutDetector.detectKey(event)
                        ?: return@onKeyEvent false
                    when (shortcut) {
                        is ShortcutEvent.Zoom -> {
                            val delta = if (shortcut.direction == ZoomDirection.In) {
                                1f + shortcut.factor
                            } else {
                                1f - shortcut.factor
                            }
                            state.applyTransform(
                                scaleFactor = delta,
                                viewportWidth = viewportWidth,
                                viewportHeight = viewportHeight,
                            )
                            true
                        }
                        is ShortcutEvent.Pan -> {
                            val panDelta = shortcut.amount.value
                            val delta = when (shortcut.direction) {
                                PanDirection.Up -> Offset(0f, panDelta)
                                PanDirection.Down -> Offset(0f, -panDelta)
                                PanDirection.Left -> Offset(panDelta, 0f)
                                PanDirection.Right -> Offset(-panDelta, 0f)
                            }
                            state.applyTransform(
                                offsetDelta = delta,
                                viewportWidth = viewportWidth,
                                viewportHeight = viewportHeight,
                            )
                            true
                        }
                    }
                }
                .focusable(),
        ) {
            content()
        }
    }
}
