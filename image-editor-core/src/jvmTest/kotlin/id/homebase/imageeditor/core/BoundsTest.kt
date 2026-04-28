package id.homebase.imageeditor.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundsTest {

    @Test fun containsAcceptsPointsInsideAndOnEdge() {
        assertTrue(Bounds.contains(0f, 0f))
        assertTrue(Bounds.contains(Bounds.LEFT, Bounds.TOP))
        assertTrue(Bounds.contains(Bounds.RIGHT, Bounds.BOTTOM))
    }

    @Test fun containsRejectsOutsidePoints() {
        assertFalse(Bounds.contains(Bounds.LEFT - 1f, 0f))
        assertFalse(Bounds.contains(0f, Bounds.BOTTOM + 1f))
    }

    @Test fun nullMatrixIsAlwaysInBounds() {
        assertTrue(Bounds.boundsRemainInBounds(null))
    }

    @Test fun identityKeepsBoundsInside() {
        assertTrue(Bounds.boundsRemainInBounds(Matrix2D()))
    }

    @Test fun translatePushingOutsideFails() {
        val m = Matrix2D().also { it.postTranslate(1f, 0f) }
        // Even a +1 unit pushes the right edge to RIGHT+1
        assertFalse(Bounds.boundsRemainInBounds(m))
    }

    @Test fun scaleDownStaysInside() {
        val m = Matrix2D().also { it.postScale(0.5f, 0.5f) }
        assertTrue(Bounds.boundsRemainInBounds(m))
    }

    @Test fun rotation45EscapesBounds() {
        // A square inscribed in [-1000,1000] rotated 45° has corners at distance ~1414.
        val m = Matrix2D().also { it.postRotate(45f) }
        assertFalse(Bounds.boundsRemainInBounds(m))
    }
}
