package id.homebase.core.media

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = state.scale,
                    scaleY = state.scale,
                    translationX = state.offset.x,
                    translationY = state.offset.y,
                )
                .pointerInput(state, viewportWidth, viewportHeight) {
                    detectZoomPanGestures(state, viewportWidth, viewportHeight)
                }
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

/**
 * Custom gesture detector that replaces `transformable`. Unlike `transformable`, which always
 * consumes pointer events once past touch slop (even when `canPan` returns false), this detector
 * selectively consumes events so HorizontalPager can take over for page swiping:
 *
 * - Not zoomed → never consume → pager handles horizontal, draggable handles vertical
 * - Zoomed + at horizontal pan edge + horizontal drag → don't consume → pager swipes pages
 * - Zoomed + not at edge → consume for panning
 * - Multi-touch → always consume for pinch zoom
 *
 * This matches Signal's PhotoView behavior where panning to the image edge transitions
 * smoothly into a page swipe.
 */
private suspend fun PointerInputScope.detectZoomPanGestures(
    state: ZoomState,
    viewportWidth: Float,
    viewportHeight: Float,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)

        var totalPan = Offset.Zero
        var totalZoomMotion = 0f
        var gestureDecided = false
        var isOurGesture = false
        var hasMultiTouch = false

        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) break

            val activePointers = event.changes.count { it.pressed }
            if (activePointers > 1) {
                hasMultiTouch = true
                isOurGesture = true
                gestureDecided = true
            }

            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()

            if (!gestureDecided) {
                totalPan += panChange
                val centroidSize = event.calculateCentroidSize(useCurrent = false)
                totalZoomMotion += abs(1 - zoomChange) * centroidSize

                if (totalZoomMotion > viewConfiguration.touchSlop ||
                    totalPan.getDistance() > viewConfiguration.touchSlop
                ) {
                    gestureDecided = true
                    isOurGesture = when {
                        !state.isZoomed -> false
                        else -> {
                            val maxX = (viewportWidth * state.scale - viewportWidth) / 2f
                            val atEdge = when {
                                totalPan.x > 0 -> state.offset.x >= maxX - EDGE_TOLERANCE
                                totalPan.x < 0 -> state.offset.x <= -maxX + EDGE_TOLERANCE
                                else -> false
                            }
                            val isHorizontalDrag = abs(totalPan.x) > abs(totalPan.y)
                            !(atEdge && isHorizontalDrag)
                        }
                    }
                }
                continue
            }

            if (isOurGesture) {
                if (hasMultiTouch || activePointers > 1) {
                    state.applyTransform(zoomChange, panChange, viewportWidth, viewportHeight)
                } else {
                    state.applyTransform(1f, panChange, viewportWidth, viewportHeight)
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

private const val EDGE_TOLERANCE = 1f
