package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalculateTargetDimensionsTest {

    @Test
    fun imageFitsWithinBounds_returnsNaturalDimensions() {
        val result = calculateTargetDimensions(100, 80, 200, 200)
        assertEquals(100 to 80, result)
    }

    @Test
    fun exactlyAtBounds_returnsNaturalDimensions() {
        val result = calculateTargetDimensions(200, 200, 200, 200)
        assertEquals(200 to 200, result)
    }

    @Test
    fun landscapeWidthLimited_scalesDown() {
        val result = calculateTargetDimensions(1000, 500, 500, 500)
        assertEquals(500 to 250, result)
    }

    @Test
    fun portraitHeightLimited_scalesDown() {
        val result = calculateTargetDimensions(500, 1000, 500, 500)
        assertEquals(250 to 500, result)
    }

    @Test
    fun squareImage_scalesDown() {
        val result = calculateTargetDimensions(1000, 1000, 500, 500)
        assertEquals(500 to 500, result)
    }

    @Test
    fun maxWidthZero_unconstrainedWidth() {
        val result = calculateTargetDimensions(800, 600, 0, 400)
        assertEquals(533 to 400, result)
    }

    @Test
    fun maxHeightZero_unconstrainedHeight() {
        val result = calculateTargetDimensions(800, 600, 400, 0)
        assertEquals(400 to 300, result)
    }

    @Test
    fun bothZero_returnsNaturalDimensions() {
        val result = calculateTargetDimensions(800, 600, 0, 0)
        assertEquals(800 to 600, result)
    }

    @Test
    fun maxWidthNegative_unconstrainedWidth() {
        val result = calculateTargetDimensions(800, 600, -1, 400)
        assertEquals(533 to 400, result)
    }

    @Test
    fun maxHeightNegative_unconstrainedHeight() {
        val result = calculateTargetDimensions(800, 600, 400, -1)
        assertEquals(400 to 300, result)
    }

    @Test
    fun veryLargeImage_scalesCorrectly() {
        val result = calculateTargetDimensions(5760, 4320, 1600, 1600)
        assertEquals(1600 to 1200, result)
    }

    @Test
    fun tinyTarget_coercesAtLeastOne() {
        val result = calculateTargetDimensions(3, 2, 1, 1)
        assertTrue(result.first >= 1, "Width must be at least 1")
        assertTrue(result.second >= 1, "Height must be at least 1")
    }

    @Test
    fun onePixelImage_returnsOnePixel() {
        val result = calculateTargetDimensions(1, 1, 100, 100)
        assertEquals(1 to 1, result)
    }

    @Test
    fun widthMuchSmallerConstraint() {
        val result = calculateTargetDimensions(2000, 1000, 100, 10000)
        assertEquals(100 to 50, result)
    }

    @Test
    fun heightMuchSmallerConstraint() {
        val result = calculateTargetDimensions(1000, 2000, 10000, 100)
        assertEquals(50 to 100, result)
    }

    @Test
    fun nonIntegerScale_roundsCorrectly() {
        val result = calculateTargetDimensions(1001, 999, 500, 500)
        assertTrue(result.first >= 1 && result.first <= 500, "Width ${result.first} out of range")
        assertTrue(result.second >= 1 && result.second <= 500, "Height ${result.second} out of range")
    }
}
