package id.homebase.imageeditor.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Matrix2DTest {

    @Test fun newMatrixIsIdentity() {
        val m = Matrix2D()
        assertTrue(m.isIdentity())
    }

    @Test fun translateMapsPoint() {
        val m = Matrix2D().also { it.postTranslate(10f, -5f) }
        val out = m.mapPoint(1f, 2f)
        assertApproxEquals(11f, out[0])
        assertApproxEquals(-3f, out[1])
    }

    @Test fun scaleMapsPoint() {
        val m = Matrix2D().also { it.postScale(2f, 3f) }
        val out = m.mapPoint(1f, 2f)
        assertApproxEquals(2f, out[0])
        assertApproxEquals(6f, out[1])
    }

    @Test fun rotate90SwapsAxesWithSign() {
        // Rotation in this package is "screen-space" (y goes down) so a
        // positive rotation maps +x to +y and +y to -x.
        val m = Matrix2D().also { it.postRotate(90f) }
        val out = m.mapPoint(1f, 0f)
        assertApproxEquals(0f, out[0])
        assertApproxEquals(1f, out[1])
        val out2 = m.mapPoint(0f, 1f)
        assertApproxEquals(-1f, out2[0])
        assertApproxEquals(0f, out2[1])
    }

    @Test fun translateThenScaleAppliesInOrder() {
        // postTranslate first, then postScale: result point = scale(translate(p))
        val m = Matrix2D().also {
            it.postTranslate(10f, 0f)
            it.postScale(2f, 1f)
        }
        val out = m.mapPoint(1f, 0f)
        assertApproxEquals(22f, out[0]) // (1 + 10) * 2
    }

    @Test fun preTranslateAppliesBeforeExisting() {
        // preTranslate applies BEFORE the existing transform: result = existing(preTranslate(p))
        val m = Matrix2D().also {
            it.postScale(2f, 1f)
            it.preTranslate(10f, 0f)
        }
        val out = m.mapPoint(1f, 0f)
        assertApproxEquals(22f, out[0]) // (1 + 10) * 2
    }

    @Test fun invertRoundTripsToIdentity() {
        val m = Matrix2D().also {
            it.postTranslate(10f, -3f)
            it.postRotate(37f)
            it.postScale(0.5f, 1.5f)
        }
        val inv = Matrix2D()
        assertTrue(m.invert(inv))
        m.preConcat(inv)
        // m * inv should be identity (within float tolerance)
        for (i in 0 until 9) {
            val expected = if (i == 0 || i == 4 || i == 8) 1f else 0f
            assertApproxEquals(expected, m.values[i], eps = 1e-3f)
        }
    }

    @Test fun setRectToRectFillStretchesBothAxes() {
        val src = RectF(0f, 0f, 10f, 20f)
        val dst = RectF(0f, 0f, 100f, 100f)
        val m = Matrix2D()
        assertTrue(m.setRectToRect(src, dst, Matrix2D.ScaleToFit.FILL))
        val mapped = RectF()
        m.mapRect(mapped, src)
        assertTrue(rectsEqual(dst, mapped, eps = 1e-3f))
    }

    @Test fun setRectToRectCenterPreservesAspect() {
        val src = RectF(0f, 0f, 10f, 20f)
        val dst = RectF(0f, 0f, 100f, 100f)
        val m = Matrix2D()
        assertTrue(m.setRectToRect(src, dst, Matrix2D.ScaleToFit.CENTER))
        val mapped = RectF()
        m.mapRect(mapped, src)
        // The narrower axis fills the dst (height 100), the wider axis is centered.
        assertApproxEquals(100f, mapped.height(), eps = 1e-3f)
        assertApproxEquals(50f, mapped.width(), eps = 1e-3f) // 10 * (100/20)
        assertApproxEquals(25f, mapped.left, eps = 1e-3f) // (100 - 50) / 2
    }

    @Test fun mapRectGivesAxisAlignedBoundingBoxAfterRotation() {
        val src = RectF(-1f, -1f, 1f, 1f) // 2x2 square centered on origin
        val m = Matrix2D().also { it.postRotate(45f) }
        val dst = RectF()
        m.mapRect(dst, src)
        // After 45° rotation a 2x2 square has bounding box ~2.828 x 2.828
        assertApproxEquals(-1.4142f, dst.left, eps = 1e-3f)
        assertApproxEquals(1.4142f, dst.right, eps = 1e-3f)
        assertApproxEquals(-1.4142f, dst.top, eps = 1e-3f)
        assertApproxEquals(1.4142f, dst.bottom, eps = 1e-3f)
    }

    @Test fun isIdentityFalseAfterTransform() {
        val m = Matrix2D().also { it.postTranslate(1f, 0f) }
        assertFalse(m.isIdentity())
    }

    @Test fun matrixCopyIsIndependent() {
        val a = Matrix2D().also { it.postTranslate(1f, 2f) }
        val b = Matrix2D(a)
        b.postScale(2f, 2f)
        // a should be unchanged
        val outA = a.mapPoint(0f, 0f)
        assertApproxEquals(1f, outA[0])
        assertApproxEquals(2f, outA[1])
    }
}

internal fun assertApproxEquals(expected: Float, actual: Float, eps: Float = 1e-4f) {
    assertEquals(expected.toDouble(), actual.toDouble(), eps.toDouble())
}
