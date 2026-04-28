package id.homebase.imageeditor.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import id.homebase.api.image.draw.PathCommand
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.core.draw.BrushType
import id.homebase.imageeditor.core.draw.Stroke as CoreStroke
import id.homebase.imageeditor.ui.DrawSnapshot
import kotlin.math.sqrt

/**
 * Paints all committed strokes plus the in-flight stroke. Paint strokes
 * (pen, marker) draw a colored stroke through the bounds → canvas chain.
 * Blur strokes draw the same path with a [ShaderBrush] sampling the
 * pre-blurred preview, so the pixels under the stroke appear blurred.
 */
@Composable
fun DrawStrokesOverlay(
    snapshot: DrawSnapshot,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (snapshot.strokes.isEmpty() && snapshot.inFlightCommands.isEmpty()) return@Canvas

        // bounds → canvas (used directly for paint strokes)
        val boundsToCanvas = Matrix2D(snapshot.viewportTransform).apply {
            preConcat(snapshot.viewLocal)
        }
        // bounds → bitmap-pixel (used for blur stroke geometry)
        val boundsToBitmap = Matrix2D()
        if (!snapshot.bitmapProjection.invert(boundsToBitmap)) {
            boundsToBitmap.reset() // degenerate; fall back to identity
        }
        // bitmap-pixel → canvas (canvas concat for blur strokes — both path
        // coords and the ImageShader sample blurredImage in bitmap-pixel
        // space, so concat'ing into bitmap-pixel space lines them up)
        val bitmapToCanvas = Matrix2D(boundsToCanvas).apply { preConcat(boundsToBitmap.inverted()) }

        val pxPerBoundsUnit = computePxPerBoundsUnit(boundsToCanvas)
        val bitmapPxPerBoundsUnit = computePxPerBoundsUnit(boundsToBitmap)

        for (stroke in snapshot.strokes) {
            drawStroke(
                stroke = stroke,
                boundsToCanvas = boundsToCanvas,
                boundsToBitmap = boundsToBitmap,
                bitmapToCanvas = bitmapToCanvas,
                pxPerBoundsUnit = pxPerBoundsUnit,
                bitmapPxPerBoundsUnit = bitmapPxPerBoundsUnit,
                blurredImage = snapshot.blurredImage,
            )
        }
        if (snapshot.inFlightBrush != null && snapshot.inFlightCommands.isNotEmpty()) {
            drawInFlight(
                snapshot = snapshot,
                boundsToCanvas = boundsToCanvas,
                boundsToBitmap = boundsToBitmap,
                bitmapToCanvas = bitmapToCanvas,
                pxPerBoundsUnit = pxPerBoundsUnit,
                bitmapPxPerBoundsUnit = bitmapPxPerBoundsUnit,
            )
        }
    }
}

private fun DrawScope.drawStroke(
    stroke: CoreStroke,
    boundsToCanvas: Matrix2D,
    boundsToBitmap: Matrix2D,
    bitmapToCanvas: Matrix2D,
    pxPerBoundsUnit: Float,
    bitmapPxPerBoundsUnit: Float,
    blurredImage: ImageBitmap?,
) {
    if (stroke.brush.compositesUnderlying) {
        drawBlurStroke(
            commands = stroke.pathCommands,
            thicknessBoundsUnits = stroke.thicknessBoundsUnits,
            cap = capFor(stroke.brush),
            boundsToBitmap = boundsToBitmap,
            bitmapToCanvas = bitmapToCanvas,
            pxPerBoundsUnit = pxPerBoundsUnit,
            bitmapPxPerBoundsUnit = bitmapPxPerBoundsUnit,
            blurredImage = blurredImage,
        )
        return
    }
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
    boundsToBitmap: Matrix2D,
    bitmapToCanvas: Matrix2D,
    pxPerBoundsUnit: Float,
    bitmapPxPerBoundsUnit: Float,
) {
    val brush = snapshot.inFlightBrush ?: return
    if (brush.compositesUnderlying) {
        drawBlurStroke(
            commands = snapshot.inFlightCommands,
            thicknessBoundsUnits = snapshot.inFlightThicknessBoundsUnits,
            cap = capFor(brush),
            boundsToBitmap = boundsToBitmap,
            bitmapToCanvas = bitmapToCanvas,
            pxPerBoundsUnit = pxPerBoundsUnit,
            bitmapPxPerBoundsUnit = bitmapPxPerBoundsUnit,
            blurredImage = snapshot.blurredImage,
        )
        return
    }
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

private fun DrawScope.drawBlurStroke(
    commands: List<PathCommand>,
    thicknessBoundsUnits: Float,
    cap: StrokeCap,
    boundsToBitmap: Matrix2D,
    bitmapToCanvas: Matrix2D,
    pxPerBoundsUnit: Float,
    bitmapPxPerBoundsUnit: Float,
    blurredImage: ImageBitmap?,
) {
    if (blurredImage == null) {
        // Pre-blurred preview hasn't loaded yet — show a placeholder so
        // the user still sees feedback while drawing.
        val pathInCanvas = commands.toComposePath(matrixForBoundsToCanvas(boundsToBitmap, bitmapToCanvas))
        drawPath(
            path = pathInCanvas,
            color = Color.Gray.copy(alpha = 0.55f),
            style = Stroke(width = thicknessBoundsUnits * pxPerBoundsUnit, cap = cap, join = StrokeJoin.Round),
        )
        return
    }
    val pathInBitmap = commands.toComposePath(boundsToBitmap)
    val widthInBitmap = thicknessBoundsUnits * bitmapPxPerBoundsUnit
    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.concat(bitmapToCanvas.toComposeMatrix())
        val brush = ShaderBrush(ImageShader(blurredImage, TileMode.Clamp, TileMode.Clamp))
        drawPath(
            path = pathInBitmap,
            brush = brush,
            style = Stroke(width = widthInBitmap, cap = cap, join = StrokeJoin.Round),
        )
        canvas.restore()
    }
}

/** Helper used only by the placeholder branch. */
private fun matrixForBoundsToCanvas(boundsToBitmap: Matrix2D, bitmapToCanvas: Matrix2D): Matrix2D {
    val out = Matrix2D(bitmapToCanvas)
    out.preConcat(boundsToBitmap)
    return out
}

private fun Matrix2D.inverted(): Matrix2D {
    val inv = Matrix2D()
    if (!invert(inv)) inv.reset()
    return inv
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
    BrushType.Blur -> StrokeCap.Round
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
