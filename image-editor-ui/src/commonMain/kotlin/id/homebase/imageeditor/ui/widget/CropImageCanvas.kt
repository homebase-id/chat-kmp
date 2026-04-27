package id.homebase.imageeditor.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import id.homebase.imageeditor.core.Bounds
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.core.RectF
import id.homebase.imageeditor.ui.MatrixSnapshot

/**
 * Draws the preview [bitmap] under the current editor transforms.
 *
 * The canvas matrix at draw time replicates Signal-Android's render pipeline:
 *
 *     view.local · flipRotate.local · mainImage.local · mainImage.editor · bitmapProjection
 *
 * `mainImage.local` is identity per Signal's invariant, so the load-bearing
 * step is `bitmapProjection` — the `setRectToRect(bitmapPixelRect, FULL_BOUNDS,
 * CENTER)` that maps bitmap pixels into the canonical bounds-space the
 * element-tree matrices expect. (Signal does this in
 * `UriGlideRenderer.java:128, 246` via `RendererContext.canvasMatrix.concat`.)
 *
 * Note: this projects the *preview* bitmap (which may be downsampled to a max
 * edge of 2048 px). The snapshot also carries `imageProjection` computed from
 * the model's natural size, but that is meant for the finalizer — the canvas
 * needs to project from whatever pixel size the preview actually is.
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
    val bitmapProjection = Matrix2D().apply {
        setRectToRect(
            src = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
            dst = Bounds.fullBounds(),
            scaleToFit = Matrix2D.ScaleToFit.CENTER,
        )
    }

    val pixelToCanvas = Matrix2D(snapshot.viewLocal)
    pixelToCanvas.preConcat(snapshot.flipRotate)
    pixelToCanvas.preConcat(snapshot.mainImageLocal)
    pixelToCanvas.preConcat(snapshot.mainImageEditor)
    pixelToCanvas.preConcat(bitmapProjection)

    val canvas = drawContext.canvas
    canvas.save()
    canvas.concat(pixelToCanvas.toComposeMatrix())
    drawImage(bitmap)
    canvas.restore()
}
