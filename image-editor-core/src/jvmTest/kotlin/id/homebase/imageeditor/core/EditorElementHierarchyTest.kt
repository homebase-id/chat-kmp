package id.homebase.imageeditor.core

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorElementHierarchyTest {

    @Test fun freshTreeHasExpectedShape() {
        val h = EditorElementHierarchy.create()
        assertNull(h.mainImage())
        // root → view → flipRotate → imageRoot/overlay → imageCrop → cropEditorElement
        assertTrue(h.root.childCount == 1)
        assertTrue(h.view.childCount == 1)
        assertTrue(h.flipRotate.childCount == 2)
        assertTrue(h.overlay.childCount == 1)
        assertTrue(h.imageCrop.childCount == 1)
    }

    @Test fun cropEditorElementHasCropFlagsSet() {
        val h = EditorElementHierarchy.create()
        assertTrue(h.cropEditorElement.flags.aspectLocked)
        assertTrue(h.cropEditorElement.flags.rotateLocked)
        assertTrue(!h.cropEditorElement.flags.selectable)
    }

    @Test fun cropRectStartsAsFullBounds() {
        val h = EditorElementHierarchy.create()
        val r = h.getCropRect()
        // With identity matrices everywhere the crop rect should equal Bounds.FULL_BOUNDS.
        assertTrue(rectsEqual(r, Bounds.fullBounds(), eps = 1e-3f))
    }

    @Test fun snapRotate90RotatesFlipRotate() {
        val h = EditorElementHierarchy.create()
        val viewport = RectF(0f, 0f, 100f, 100f)
        h.flipRotate(degrees = 90f, scaleX = 1, scaleY = 1, visibleViewPort = viewport)
        // After 90° rotation the rotation angle (radians) should be -π/2 in
        // our screen-space convention (or equivalently 3π/2). The magnitude
        // ought to be ~π/2.
        val angle = MatrixUtils.getRotationAngle(h.flipRotate.localMatrix)
        val pi2 = (kotlin.math.PI / 2).toFloat()
        assertTrue(kotlin.math.abs(kotlin.math.abs(angle) - pi2) < 1e-3f, "angle=$angle")
    }
}
