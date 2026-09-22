package id.homebase.imageeditor.ui

import id.homebase.imageeditor.core.Bounds
import id.homebase.imageeditor.core.ControlPoint
import id.homebase.imageeditor.core.CropHandles
import id.homebase.imageeditor.core.EditorModel
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.core.RectF
import id.homebase.imageeditor.core.Size
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The core `CropHandles` tests compose the crop-to-canvas chain themselves;
 * this pins the chain the cropper's pointer handler actually hit-tests against.
 */
class MatrixSnapshotCropHandleTest {

    private fun model(quarterTurns: Int, flipped: Boolean): EditorModel {
        val model = EditorModel.create()
        model.onImageReady(Size(600, 450))
        model.setVisibleViewPort(RectF(0f, 0f, 1080f, 1920f))
        model.setCropAspectLock(false)
        repeat(quarterTurns) { model.rotate90Clockwise() }
        if (flipped) model.flipHorizontal()
        return model
    }

    @Test
    fun snapshotCropToCanvasResolvesEachVisibleCornerToTheHandleDrawnThere() {
        for (quarterTurns in 0..3) {
            for (flipped in listOf(false, true)) {
                val snapshot = MatrixSnapshot.capture(model(quarterTurns, flipped))
                val m: Matrix2D = snapshot.cropToCanvas

                val rect = RectF()
                m.mapRect(rect, Bounds.fullBounds())
                val corners = listOf(
                    "topLeft" to Pair(rect.left, rect.top),
                    "topRight" to Pair(rect.right, rect.top),
                    "bottomRight" to Pair(rect.right, rect.bottom),
                    "bottomLeft" to Pair(rect.left, rect.bottom),
                )
                val opposites = mapOf(
                    "topLeft" to Pair(rect.right, rect.bottom),
                    "topRight" to Pair(rect.left, rect.bottom),
                    "bottomRight" to Pair(rect.left, rect.top),
                    "bottomLeft" to Pair(rect.right, rect.top),
                )

                for ((name, touch) in corners) {
                    val where = "turns=$quarterTurns flipped=$flipped $name"
                    val cp: ControlPoint? =
                        CropHandles.hitTest(touch.first, touch.second, m, 90f)
                    assertNotNull(cp, "$where: no handle hit")
                    assertClose(touch, m.mapPoint(cp.x, cp.y), "$where: $cp is drawn elsewhere")
                    assertClose(
                        opposites.getValue(name),
                        m.mapPoint(cp.opposite().x, cp.opposite().y),
                        "$where: anchor ${cp.opposite()} is not the opposite corner",
                    )
                }
            }
        }
    }

    private fun assertClose(expected: Pair<Float, Float>, actual: FloatArray, message: String) {
        assertTrue(
            abs(expected.first - actual[0]) <= 0.5f && abs(expected.second - actual[1]) <= 0.5f,
            "$message: expected (${expected.first}, ${expected.second}) " +
                "but was (${actual[0]}, ${actual[1]})",
        )
    }
}
