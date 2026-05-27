package id.homebase.core.media

import androidx.compose.ui.geometry.Offset

sealed interface ZoomFocalPoint {
    fun computeOffset(
        currentScale: Float,
        targetScale: Float,
        currentOffset: Offset,
        viewportWidth: Float,
        viewportHeight: Float,
        centroid: Offset,
    ): Offset

    data object ViewportCenter : ZoomFocalPoint {
        override fun computeOffset(
            currentScale: Float,
            targetScale: Float,
            currentOffset: Offset,
            viewportWidth: Float,
            viewportHeight: Float,
            centroid: Offset,
        ): Offset = Offset.Zero
    }

    data object ZoomAroundCentroid : ZoomFocalPoint {
        override fun computeOffset(
            currentScale: Float,
            targetScale: Float,
            currentOffset: Offset,
            viewportWidth: Float,
            viewportHeight: Float,
            centroid: Offset,
        ): Offset {
            val focusFractionX = centroid.x / viewportWidth
            val focusFractionY = centroid.y / viewportHeight
            val maxOffsetX = (viewportWidth * targetScale - viewportWidth) / 2f
            val maxOffsetY = (viewportHeight * targetScale - viewportHeight) / 2f
            return if (targetScale > 1f) {
                Offset(
                    x = ((0.5f - focusFractionX) * viewportWidth * targetScale)
                        .coerceIn(-maxOffsetX, maxOffsetX),
                    y = ((0.5f - focusFractionY) * viewportHeight * targetScale)
                        .coerceIn(-maxOffsetY, maxOffsetY),
                )
            } else {
                Offset.Zero
            }
        }
    }
}
