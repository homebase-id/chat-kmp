package id.homebase.imageeditor.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlPointTest {

    @Test fun cornerOppositesAreDiagonals() {
        assertEquals(ControlPoint.BOTTOM_RIGHT, ControlPoint.TOP_LEFT.opposite())
        assertEquals(ControlPoint.BOTTOM_LEFT, ControlPoint.TOP_RIGHT.opposite())
        assertEquals(ControlPoint.TOP_RIGHT, ControlPoint.BOTTOM_LEFT.opposite())
        assertEquals(ControlPoint.TOP_LEFT, ControlPoint.BOTTOM_RIGHT.opposite())
    }

    @Test fun edgeOppositesAreOppositeEdges() {
        assertEquals(ControlPoint.CENTER_RIGHT, ControlPoint.CENTER_LEFT.opposite())
        assertEquals(ControlPoint.CENTER_LEFT, ControlPoint.CENTER_RIGHT.opposite())
        assertEquals(ControlPoint.BOTTOM_CENTER, ControlPoint.TOP_CENTER.opposite())
    }

    @Test fun isCenterClassification() {
        assertTrue(ControlPoint.CENTER_LEFT.isCenter())
        assertTrue(ControlPoint.TOP_CENTER.isCenter())
        assertFalse(ControlPoint.TOP_LEFT.isCenter())
        assertFalse(ControlPoint.BOTTOM_RIGHT.isCenter())
    }

    @Test fun isHorizontalCenter() {
        assertTrue(ControlPoint.CENTER_LEFT.isHorizontalCenter())
        assertTrue(ControlPoint.CENTER_RIGHT.isHorizontalCenter())
        assertFalse(ControlPoint.TOP_CENTER.isHorizontalCenter())
    }

    @Test fun scaleAndRotateIsClassified() {
        assertTrue(ControlPoint.SCALE_ROT_LEFT.isScaleAndRotateThumb())
        assertTrue(ControlPoint.SCALE_ROT_RIGHT.isScaleAndRotateThumb())
        assertFalse(ControlPoint.TOP_LEFT.isScaleAndRotateThumb())
    }
}
