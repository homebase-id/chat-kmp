package id.homebase.imageeditor.core

import kotlin.math.hypot

/**
 * Maps between the crop frame's on-screen handles and the [ControlPoint]s that
 * [id.homebase.imageeditor.core.session.ThumbDragEditSession] drives.
 */
object CropHandles {

    val CORNERS: List<ControlPoint> = listOf(
        ControlPoint.TOP_LEFT,
        ControlPoint.TOP_RIGHT,
        ControlPoint.BOTTOM_RIGHT,
        ControlPoint.BOTTOM_LEFT,
    )

    val EDGES: List<ControlPoint> = listOf(
        ControlPoint.CENTER_LEFT,
        ControlPoint.CENTER_RIGHT,
        ControlPoint.TOP_CENTER,
        ControlPoint.BOTTOM_CENTER,
    )

    /**
     * Canvas-pixel position of [controlPoint]'s handle.
     *
     * @param cropToCanvas canonical [Bounds] space to canvas pixels.
     */
    fun handlePosition(controlPoint: ControlPoint, cropToCanvas: Matrix2D): PointF {
        // Map the canonical point rather than naming a corner of the mapped
        // bounding box: a committed flip/snap-rotate permutes which canonical
        // corner is drawn where, and the drag session anchors on the canonical
        // opposite().
        val p = cropToCanvas.mapPoint(controlPoint.x, controlPoint.y)
        return PointF(p[0], p[1])
    }

    /** Nearest [candidates] handle within [hitRadiusPx] of the canvas point, or null. */
    fun hitTest(
        canvasX: Float,
        canvasY: Float,
        cropToCanvas: Matrix2D,
        hitRadiusPx: Float,
        candidates: List<ControlPoint> = CORNERS,
    ): ControlPoint? {
        var best: ControlPoint? = null
        var bestDist = Float.MAX_VALUE
        for (cp in candidates) {
            val handle = handlePosition(cp, cropToCanvas)
            val d = hypot(canvasX - handle.x, canvasY - handle.y)
            if (d <= hitRadiusPx && d < bestDist) {
                best = cp
                bestDist = d
            }
        }
        return best
    }
}
