package id.homebase.imageeditor.ui.widget

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class ColorSliderStopsTest {

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun rgb(value: Int): String =
        "${(value shr 16) and 0xFF},${(value shr 8) and 0xFF},${value and 0xFF}"

    private fun assertColorAt(position: Float, expected: Int) {
        assertEquals(rgb(expected), rgb(colorForPosition(position)), "at position $position")
    }

    @Test
    fun everyGradientStopResolvesToThatStopsColor() {
        val expected = listOf(
            0.00f to Color.Black,
            0.05f to Color.Black,
            0.18f to Color.Red,
            0.32f to Color.Yellow,
            0.46f to Color.Green,
            0.60f to Color.Cyan,
            0.74f to Color.Blue,
            0.88f to Color.Magenta,
            0.95f to Color.White,
            1.00f to Color.White,
        )
        assertEquals(expected, ColorSliderStops.toList())
        for ((position, color) in expected) {
            assertColorAt(
                position,
                argb(
                    (color.red * 255f).toInt(),
                    (color.green * 255f).toInt(),
                    (color.blue * 255f).toInt(),
                ),
            )
        }
    }

    @Test
    fun midpointsAreTheRgbLerpOfTheirNeighbours() {
        assertColorAt(0.25f, argb(255, 128, 0))
        assertColorAt(0.67f, argb(0, 128, 255))
        assertColorAt(0.115f, argb(127, 0, 0))
    }

    @Test
    fun positionsOutsideTheBarClampToTheEndpoints() {
        assertColorAt(-1f, argb(0, 0, 0))
        assertColorAt(0f, argb(0, 0, 0))
        assertColorAt(1f, argb(255, 255, 255))
        assertColorAt(2f, argb(255, 255, 255))
    }

    @Test
    fun redSitsAtTheStopTheBarDrawsItAtNotWhereTheOldLinearRampPutIt() {
        assertColorAt(0.05f, argb(0, 0, 0))
        assertColorAt(0.18f, argb(255, 0, 0))
    }
}
