package id.homebase.imageeditor.core.session

import id.homebase.imageeditor.core.ControlPoint
import id.homebase.imageeditor.core.EditorElement
import id.homebase.imageeditor.core.EditorModel
import id.homebase.imageeditor.core.Matrix2D
import id.homebase.imageeditor.core.PointF
import id.homebase.imageeditor.core.RectF
import id.homebase.imageeditor.core.Size
import id.homebase.imageeditor.core.assertApproxEquals
import kotlin.test.Test
import kotlin.test.assertNotNull

class EditSessionTest {

    @Test fun dragSessionTranslatesEditorMatrix() {
        val m = EditorModel.create()
        m.onImageReady(Size(1000, 1000))
        m.setVisibleViewPort(RectF(0f, 0f, 1000f, 1000f))
        val main = m.hierarchy.mainImage()!!
        // identity inverse — element-space == screen-space for this test
        val session = ElementDragEditSession.startDrag(main, Matrix2D(), PointF(0f, 0f))
        assertNotNull(session)
        session.movePoint(0, PointF(100f, 50f))
        // editor matrix should now translate (100, 50)
        val out = main.editorMatrix.mapPoint(0f, 0f)
        assertApproxEquals(100f, out[0])
        assertApproxEquals(50f, out[1])
    }

    @Test fun thumbDragOnCornerScalesAroundOpposite() {
        val m = EditorModel.create()
        m.onImageReady(Size(1000, 1000))
        m.setVisibleViewPort(RectF(0f, 0f, 1000f, 1000f))
        // Start a thumb drag on TOP_LEFT. Inverse + thumbContainer matrices
        // are identity so the canonical -1000..1000 space is element-space.
        val session = ThumbDragEditSession.startDrag(
            selected = m.hierarchy.cropEditorElement,
            inverseViewModelMatrix = Matrix2D(),
            thumbContainerRelativeMatrix = Matrix2D(),
            controlPoint = ControlPoint.TOP_LEFT,
            point = PointF(-1000f, -1000f),
        )
        assertNotNull(session)
        // Drag the TOP_LEFT corner halfway toward the centre.
        session.movePoint(0, PointF(0f, 0f))
        // Aspect-locked — uniform scale should be ~0.5 (half-distance).
        val em = m.hierarchy.cropEditorElement.editorMatrix
        val out = em.mapPoint(-1000f, -1000f)
        // The corner should now be at the origin (we dragged it there).
        assertApproxEquals(0f, out[0], eps = 1e-2f)
        assertApproxEquals(0f, out[1], eps = 1e-2f)
    }

    @Test fun dragSessionCommitMovesToLocal() {
        val tree = EditorModel.create()
        tree.onImageReady(Size(1000, 1000))
        tree.setVisibleViewPort(RectF(0f, 0f, 1000f, 1000f))
        val main = tree.hierarchy.mainImage()!!
        val before = Matrix2D(main.localMatrix)
        val session = ElementDragEditSession.startDrag(main, Matrix2D(), PointF(0f, 0f))!!
        session.movePoint(0, PointF(50f, 0f))
        session.commit()
        // editorMatrix should reset.
        kotlin.test.assertTrue(main.editorMatrix.isIdentity())
        // localMatrix should differ from before.
        val outBefore = before.mapPoint(0f, 0f)
        val outAfter = main.localMatrix.mapPoint(0f, 0f)
        kotlin.test.assertTrue(outAfter[0] != outBefore[0])
    }
}

@Suppress("unused")
private fun fakeUnused(e: EditorElement) {
}
