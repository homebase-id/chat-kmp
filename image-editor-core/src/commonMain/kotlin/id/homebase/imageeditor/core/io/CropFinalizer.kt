package id.homebase.imageeditor.core.io

import id.homebase.api.image.ImageFormat
import id.homebase.api.image.ImageResult
import id.homebase.api.image.ImageUtils
import id.homebase.imageeditor.core.EditorModel
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.core.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Renders the current state of an [EditorModel] into a final cropped image.
 *
 * Pipeline:
 *   1. Compute the output dimensions in natural-pixel space.
 *   2. Optionally cap to a maximum edge length.
 *   3. Build a `naturalToOutput` matrix.
 *   4. Call [ImageUtils.warpAffine] to rasterize.
 */
object CropFinalizer {

    /** No cap by default — emit at native pixel resolution. */
    const val NO_MAX_EDGE: Int = 0

    /**
     * Finalize the current crop.
     *
     * @param originalBytes encoded source bytes (use [CropPreprocessor.prepare]
     *                      to keep these around)
     * @param model the editor model holding the user's adjustments
     * @param maxEdge optional cap on the longest output edge in pixels.
     *                Pass [NO_MAX_EDGE] to use full natural-pixel resolution.
     */
    fun finalize(
        originalBytes: ByteArray,
        model: EditorModel,
        outputFormat: ImageFormat = ImageFormat.JPEG,
        quality: Int = 90,
        maxEdge: Int = NO_MAX_EDGE,
    ): ImageResult {
        val natural = model.getOutputSize()
        var outW = max(1, natural.width)
        var outH = max(1, natural.height)
        if (maxEdge > 0) {
            val longest = max(outW, outH)
            if (longest > maxEdge) {
                val scale = maxEdge.toFloat() / longest
                outW = max(1, (outW * scale).toInt())
                outH = max(1, (outH * scale).toInt())
            }
        }

        val matrix = naturalToOutputMatrix(model, outW, outH)
        return ImageUtils.warpAffine(
            srcBytes = originalBytes,
            matrix9 = matrix.values,
            outputWidth = outW,
            outputHeight = outH,
            fillColorArgb = 0x00000000,
            outputFormat = outputFormat,
            quality = quality,
        )
    }

    /**
     * Matrix that maps natural-pixel coordinates of the source image to
     * pixel coordinates of the final output bitmap.
     *
     *   `viewToOutput * flipRotate.local * mainImage.local * mainImage.editor`
     *
     * where `viewToOutput = setRectToRect(getCropRect(), [0..outW, 0..outH], FILL)`.
     */
    fun naturalToOutputMatrix(model: EditorModel, outW: Int, outH: Int): Matrix2D {
        val cropRect = model.getCropRect()
        val outRect = RectF(0f, 0f, outW.toFloat(), outH.toFloat())
        val viewToOutput = Matrix2D().also {
            it.setRectToRect(cropRect, outRect, Matrix2D.ScaleToFit.FILL)
        }
        val natToView = model.naturalToViewMatrix()
        val out = Matrix2D(viewToOutput)
        out.preConcat(natToView)
        // out is now natural -> output (in view-space then to output).
        return out
    }

    /**
     * Compose the same matrix without an [EditorModel] — useful for tests.
     */
    fun naturalToOutputMatrixForCropRect(
        natToView: Matrix2D,
        cropRect: RectF,
        outW: Int,
        outH: Int,
    ): Matrix2D {
        val outRect = RectF(0f, 0f, outW.toFloat(), outH.toFloat())
        val viewToOutput = Matrix2D().also {
            it.setRectToRect(cropRect, outRect, Matrix2D.ScaleToFit.FILL)
        }
        val out = Matrix2D(viewToOutput)
        out.preConcat(natToView)
        return out
    }

    @Suppress("unused")
    private fun cap(value: Int, maxValue: Int): Int = min(value, maxValue)
}
