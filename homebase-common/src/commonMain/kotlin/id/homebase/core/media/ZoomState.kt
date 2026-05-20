package id.homebase.core.media

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

@Stable
class ZoomState(
    val minScale: Float = 1f,
    val maxScale: Float = 5f,
) {
    init {
        require(minScale > 0f) { "minScale must be positive, was $minScale" }
        require(maxScale >= minScale) { "maxScale ($maxScale) must be >= minScale ($minScale)" }
    }

    var scale by mutableFloatStateOf(minScale)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    val isZoomed: Boolean get() = scale > minScale

    fun resetZoom() {
        scale = minScale
        offset = Offset.Zero
    }

    fun toggleDoubleTapZoom() {
        if (isZoomed) {
            resetZoom()
        } else {
            scale = DOUBLE_TAP_SCALE.coerceAtMost(maxScale)
        }
    }

    fun applyTransform(
        scaleFactor: Float = 1f,
        offsetDelta: Offset = Offset.Zero,
        viewportWidth: Float = 0f,
        viewportHeight: Float = 0f,
    ) {
        scale = (scale * scaleFactor).coerceIn(minScale, maxScale)
        if (scale > minScale) {
            val newOffset = offset + (offsetDelta * VELOCITY_FACTOR)
            val maxOffsetX = (viewportWidth * scale - viewportWidth) / 2f
            val maxOffsetY = (viewportHeight * scale - viewportHeight) / 2f
            offset = Offset(
                x = newOffset.x.coerceIn(-maxOffsetX, maxOffsetX),
                y = newOffset.y.coerceIn(-maxOffsetY, maxOffsetY),
            )
        } else {
            offset = Offset.Zero
        }
    }

    companion object {
        internal const val DOUBLE_TAP_SCALE = 2f
        internal const val VELOCITY_FACTOR = 2f
    }
}
