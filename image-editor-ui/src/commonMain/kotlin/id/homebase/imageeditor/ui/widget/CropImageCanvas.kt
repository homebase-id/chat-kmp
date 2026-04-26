package id.homebase.imageeditor.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.ui.MatrixSnapshot

/**
 * Draws the preview [bitmap] under the current editor transforms.
 *
 * Transform chain applied:
 *   `view.local * flipRotate.local * mainImage.local * mainImage.editor`
 *
 * All in canvas-pixel space, since [view.local] is set up by [model.setVisibleViewPort]
 * to map the canonical Bounds into the actual on-screen viewport.
 */
@Composable
fun CropImageCanvas(
    bitmap: ImageBitmap?,
    snapshot: MatrixSnapshot,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (bitmap == null) return@Canvas
        drawTransformedImage(bitmap, snapshot)
    }
}

private fun DrawScope.drawTransformedImage(bitmap: ImageBitmap, snapshot: MatrixSnapshot) {
    // Build the natural-pixel-to-canvas matrix.
    val natToCanvas = Matrix2D(snapshot.viewLocal)
    natToCanvas.preConcat(snapshot.flipRotate)
    natToCanvas.preConcat(snapshot.mainImageLocal)
    natToCanvas.preConcat(snapshot.mainImageEditor)
    val composeMatrix = natToCanvas.toComposeMatrix()

    val canvas = drawContext.canvas
    canvas.save()
    canvas.concat(composeMatrix)
    drawImage(bitmap)
    canvas.restore()
    @Suppress("UNUSED_EXPRESSION") Stroke // keep import resolved if reused later
}
