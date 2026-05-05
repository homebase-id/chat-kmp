package id.homebase.imageeditor.core.io

import id.homebase.api.image.ImageFormat
import id.homebase.api.image.ImageResult
import id.homebase.api.image.ImageUtils
import id.homebase.api.image.draw.PathCommand
import id.homebase.api.image.draw.StrokeCommand
import id.homebase.api.image.draw.StrokeKind
import id.homebase.imageeditor.core.Bounds
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.core.RectF
import id.homebase.imageeditor.core.Size
import id.homebase.imageeditor.core.draw.BrushType
import id.homebase.imageeditor.core.draw.DrawingModel
import id.homebase.imageeditor.core.draw.Stroke
import kotlin.math.sqrt

/**
 * Renders a [DrawingModel]'s strokes onto the source image at natural-pixel
 * resolution and re-encodes.
 *
 * Strokes are stored in canonical bounds-space (`[-1000, 1000]^2`) — the
 * same coordinate convention the cropper uses. We invert the
 * `setRectToRect(naturalRect, fullBounds, CENTER)` projection to walk every
 * path command and thickness back into source pixels, then hand the list to
 * the platform-specific [ImageUtils.drawStrokes].
 *
 * Only Pen and Highlighter are supported here. Blur strokes (if any are ever
 * present) are silently skipped — the UI layer should not let the user draw
 * blur strokes until the platform support lands.
 */
object DrawFinalizer {

    fun finalize(
        originalBytes: ByteArray,
        naturalSize: Size,
        model: DrawingModel,
        outputFormat: ImageFormat = ImageFormat.JPEG,
        quality: Int = 90,
    ): ImageResult {
        val cmds = ArrayList<StrokeCommand>(model.strokes.size)
        val boundsToNatural = boundsToNaturalMatrix(naturalSize)
        // For uniform projections (CENTER scale-to-fit), inv x-axis length
        // and y-axis length are equal. Use the average just in case future
        // changes break that assumption.
        val pixelsPerBoundsUnit = pixelsPerBoundsUnit(boundsToNatural)
        for (s in model.strokes) {
            cmds.add(strokeToCommand(s, boundsToNatural, pixelsPerBoundsUnit))
        }
        return ImageUtils.drawStrokes(
            srcBytes = originalBytes,
            strokes = cmds,
            outputFormat = outputFormat,
            quality = quality,
        )
    }

    private fun boundsToNaturalMatrix(naturalSize: Size): Matrix2D {
        val natRect = RectF(0f, 0f, naturalSize.width.toFloat(), naturalSize.height.toFloat())
        val natToBounds = Matrix2D().apply {
            setRectToRect(natRect, Bounds.fullBounds(), Matrix2D.ScaleToFit.CENTER)
        }
        val boundsToNat = Matrix2D()
        check(natToBounds.invert(boundsToNat)) { "imageProjection should be invertible" }
        return boundsToNat
    }

    private fun pixelsPerBoundsUnit(boundsToNatural: Matrix2D): Float {
        // Map a unit vector (1,0) and read the resulting x-displacement.
        // mapPoints applies translation, so subtract the origin.
        val origin = floatArrayOf(0f, 0f)
        val unit = floatArrayOf(1f, 0f)
        val o = FloatArray(2); val u = FloatArray(2)
        boundsToNatural.mapPoints(o, origin, count = 1)
        boundsToNatural.mapPoints(u, unit, count = 1)
        val dx = u[0] - o[0]
        val dy = u[1] - o[1]
        return sqrt(dx * dx + dy * dy)
    }

    private fun strokeToCommand(
        stroke: Stroke,
        boundsToNatural: Matrix2D,
        pixelsPerBoundsUnit: Float,
    ): StrokeCommand {
        val mappedCommands = stroke.pathCommands.map { it.mapped(boundsToNatural) }
        return StrokeCommand(
            cap = stroke.brush.cap,
            colorArgb = applyAlpha(stroke.colorArgb, stroke.brush.alpha),
            thicknessPx = stroke.thicknessBoundsUnits * pixelsPerBoundsUnit,
            pathCommands = mappedCommands,
            kind = if (stroke.brush.compositesUnderlying) StrokeKind.BLUR else StrokeKind.PAINT,
        )
    }

    private fun PathCommand.mapped(m: Matrix2D): PathCommand {
        val out = FloatArray(6)
        return when (this) {
            is PathCommand.MoveTo -> {
                m.mapPoint(x, y, out)
                PathCommand.MoveTo(out[0], out[1])
            }
            is PathCommand.LineTo -> {
                m.mapPoint(x, y, out)
                PathCommand.LineTo(out[0], out[1])
            }
            is PathCommand.CubicTo -> {
                val src = floatArrayOf(c1x, c1y, c2x, c2y, x, y)
                m.mapPoints(out, src, count = 3)
                PathCommand.CubicTo(out[0], out[1], out[2], out[3], out[4], out[5])
            }
        }
    }

    private fun applyAlpha(argb: Int, brushAlpha: Int): Int {
        val baseAlpha = (argb ushr 24) and 0xFF
        val combined = (baseAlpha * brushAlpha) / 0xFF
        return (combined shl 24) or (argb and 0x00FFFFFF)
    }

    @Suppress("unused")
    private fun BrushType.usesUnderlying() = this.compositesUnderlying
}
