package id.homebase.imageeditor.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import id.homebase.api.image.draw.PathCommand
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.core.draw.BrushType
import id.homebase.imageeditor.core.draw.Stroke as CoreStroke
import id.homebase.imageeditor.ui.DrawSnapshot
import kotlin.math.sqrt

/**
 * Paints all committed strokes plus the in-flight stroke, all in bounds-space
 * mapped through `viewportTransform · viewLocal`. Pen and Highlighter
 * brushes only — Blur is drawn elsewhere (or skipped for now).
 */
@Composable
fun DrawStrokesOverlay(
    snapshot: DrawSnapshot,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (snapshot.strokes.isEmpty() && snapshot.inFlightCommands.isEmpty()) return@Canvas

        val boundsToCanvas = Matrix2D(snapshot.viewportTransform).apply {
            preConcat(snapshot.viewLocal)
        }
        val pxPerBoundsUnit = computePxPerBoundsUnit(boundsToCanvas)

        for (stroke in snapshot.strokes) {
            drawStroke(stroke, boundsToCanvas, pxPerBoundsUnit)
        }
        if (snapshot.inFlightBrush != null && snapshot.inFlightCommands.isNotEmpty()) {
            drawInFlight(snapshot, boundsToCanvas, pxPerBoundsUnit)
        }
    }
}

private fun DrawScope.drawStroke(
    stroke: CoreStroke,
    boundsToCanvas: Matrix2D,
    pxPerBoundsUnit: Float,
) {
    if (stroke.brush.compositesUnderlying) return // blur not yet supported here
    val path = stroke.pathCommands.toComposePath(boundsToCanvas)
    val argb = applyAlpha(stroke.colorArgb, stroke.brush.alpha)
    val color = Color(argb.toLong() and 0xFFFFFFFFL)
    val width = stroke.thicknessBoundsUnits * pxPerBoundsUnit
    val cap = capFor(stroke.brush)
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = width, cap = cap, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drawInFlight(
    snapshot: DrawSnapshot,
    boundsToCanvas: Matrix2D,
    pxPerBoundsUnit: Float,
) {
    val brush = snapshot.inFlightBrush ?: return
    if (brush.compositesUnderlying) return
    val path = snapshot.inFlightCommands.toComposePath(boundsToCanvas)
    val argb = applyAlpha(snapshot.inFlightColorArgb, brush.alpha)
    val color = Color(argb.toLong() and 0xFFFFFFFFL)
    val width = snapshot.inFlightThicknessBoundsUnits * pxPerBoundsUnit
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = width, cap = capFor(brush), join = StrokeJoin.Round),
    )
}

private fun List<PathCommand>.toComposePath(m: Matrix2D): Path {
    val path = Path()
    val out = FloatArray(2)
    val out6 = FloatArray(6)
    for (cmd in this) when (cmd) {
        is PathCommand.MoveTo -> {
            m.mapPoint(cmd.x, cmd.y, out)
            path.moveTo(out[0], out[1])
        }
        is PathCommand.LineTo -> {
            m.mapPoint(cmd.x, cmd.y, out)
            path.lineTo(out[0], out[1])
        }
        is PathCommand.CubicTo -> {
            val src = floatArrayOf(cmd.c1x, cmd.c1y, cmd.c2x, cmd.c2y, cmd.x, cmd.y)
            m.mapPoints(out6, src, count = 3)
            path.cubicTo(out6[0], out6[1], out6[2], out6[3], out6[4], out6[5])
        }
    }
    return path
}

private fun capFor(brush: BrushType): StrokeCap = when (brush) {
    BrushType.Pen -> StrokeCap.Round
    BrushType.Highlighter -> StrokeCap.Square
}

private fun applyAlpha(argb: Int, brushAlpha: Int): Int {
    val baseAlpha = (argb ushr 24) and 0xFF
    val combined = (baseAlpha * brushAlpha) / 0xFF
    return (combined shl 24) or (argb and 0x00FFFFFF)
}

private fun computePxPerBoundsUnit(m: Matrix2D): Float {
    val o = m.mapPoint(0f, 0f)
    val u = m.mapPoint(1f, 0f)
    val dx = u[0] - o[0]
    val dy = u[1] - o[1]
    return sqrt(dx * dx + dy * dy)
}
