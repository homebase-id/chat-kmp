package id.homebase.core.media

import androidx.compose.ui.geometry.Offset

fun interface DoubleClickToZoomListener {
    suspend fun onDoubleClick(
        state: ZoomState,
        centroid: Offset,
        viewportWidth: Float,
        viewportHeight: Float,
    )

    companion object {
        fun cycle(
            maxZoomFactor: Float? = null,
            focalPoint: ZoomFocalPoint = ZoomFocalPoint.ZoomAroundCentroid,
        ): DoubleClickToZoomListener = DoubleClickToZoomListener { state, centroid, vw, vh ->
            val maxZoom = maxZoomFactor ?: state.maxScale
            if (state.isZoomed) {
                state.animateToZoom(state.minScale, Offset.Zero)
            } else {
                val targetScale = ZoomState.DOUBLE_TAP_SCALE.coerceAtMost(maxZoom)
                val targetOffset = focalPoint.computeOffset(
                    currentScale = state.scale,
                    targetScale = targetScale,
                    currentOffset = state.offset,
                    viewportWidth = vw,
                    viewportHeight = vh,
                    centroid = centroid,
                )
                state.animateToZoom(targetScale, targetOffset)
            }
        }
    }
}
