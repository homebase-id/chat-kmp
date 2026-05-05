package id.homebase.imageeditor.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.ui.DrawSnapshot

/**
 * Paints the source bitmap under the draw editor's transform chain:
 *
 *     viewportTransform · viewLocal · bitmapProjection
 *
 * Same architecture as the cropper's `CropImageCanvas` minus the
 * flipRotate/mainImage stages — draw never edits those.
 */
@Composable
fun DrawImageCanvas(
    bitmap: ImageBitmap?,
    snapshot: DrawSnapshot,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (bitmap == null) return@Canvas
        drawTransformedImage(bitmap, snapshot)
    }
}

private fun DrawScope.drawTransformedImage(bitmap: ImageBitmap, snapshot: DrawSnapshot) {
    val pixelToCanvas = Matrix2D(snapshot.viewportTransform)
    pixelToCanvas.preConcat(snapshot.viewLocal)
    pixelToCanvas.preConcat(snapshot.bitmapProjection)

    val canvas = drawContext.canvas
    canvas.save()
    canvas.concat(pixelToCanvas.toComposeMatrix())
    drawImage(bitmap)
    canvas.restore()
}
