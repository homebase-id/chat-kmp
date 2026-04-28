package id.homebase.api.image.draw

/**
 * One segment of a stroke path. Coordinates are in whatever space the caller
 * passes in (bounds-space at editor commit time, natural-pixel space when
 * passed to [id.homebase.api.image.ImageUtils.drawStrokes]).
 */
sealed class PathCommand {
    data class MoveTo(val x: Float, val y: Float) : PathCommand()
    data class LineTo(val x: Float, val y: Float) : PathCommand()
    data class CubicTo(
        val c1x: Float, val c1y: Float,
        val c2x: Float, val c2y: Float,
        val x: Float, val y: Float,
    ) : PathCommand()
}

enum class StrokeCap { Round, Square }

/**
 * Wire-format stroke for the platform rasterizer
 * ([id.homebase.api.image.ImageUtils.drawStrokes]). Coordinates and
 * [thicknessPx] are in **source-pixel space** of the input image.
 *
 * @property colorArgb final ARGB to draw with — alpha already baked from
 *   the brush's per-brush alpha multiplier.
 */
data class StrokeCommand(
    val cap: StrokeCap,
    val colorArgb: Int,
    val thicknessPx: Float,
    val pathCommands: List<PathCommand>,
)
