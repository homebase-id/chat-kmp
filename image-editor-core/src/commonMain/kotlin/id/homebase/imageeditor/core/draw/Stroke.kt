package id.homebase.imageeditor.core.draw

import id.homebase.api.image.draw.PathCommand

/**
 * Immutable record of one committed stroke.
 *
 * Coordinates and [thicknessBoundsUnits] are stored in canonical bounds-space
 * `[-1000, 1000]^2` — the same coordinate system the cropper uses. The
 * finalizer projects them into natural-pixel space at save time.
 *
 * [pathCommands] is precomputed at commit so the screen renderer doesn't have
 * to resmooth on every frame.
 */
data class Stroke(
    val brush: BrushType,
    val colorArgb: Int,
    val thicknessBoundsUnits: Float,
    val rawPointsXY: FloatArray,
    val pathCommands: List<PathCommand>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Stroke) return false
        return brush == other.brush &&
            colorArgb == other.colorArgb &&
            thicknessBoundsUnits == other.thicknessBoundsUnits &&
            rawPointsXY.contentEquals(other.rawPointsXY) &&
            pathCommands == other.pathCommands
    }

    override fun hashCode(): Int {
        var result = brush.hashCode()
        result = 31 * result + colorArgb
        result = 31 * result + thicknessBoundsUnits.hashCode()
        result = 31 * result + rawPointsXY.contentHashCode()
        result = 31 * result + pathCommands.hashCode()
        return result
    }
}
