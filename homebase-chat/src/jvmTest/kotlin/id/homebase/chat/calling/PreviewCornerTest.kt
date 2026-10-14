package id.homebase.chat.calling

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewCornerTest {
    private val corners = previewCorners(width = 1000f, height = 800f, pipW = 200f, pipH = 120f, margin = 16f, top = 80f, bottomReserve = 128f)

    @Test
    fun cornersSitInsideTheMarginsAndAboveTheDock() {
        assertEquals(listOf(Offset(16f, 80f), Offset(784f, 80f), Offset(16f, 552f), Offset(784f, 552f)), corners)
    }

    @Test
    fun dropNearACornerSnapsToIt() {
        assertEquals(corners[1], nearestPreviewCorner(corners, Offset(700f, 150f)))
        assertEquals(corners[2], nearestPreviewCorner(corners, Offset(100f, 400f)))
        assertEquals(corners[3], nearestPreviewCorner(corners, Offset(600f, 500f)))
    }

    @Test
    fun shortWindowClampsBottomCornersToTheTop() {
        val short = previewCorners(1000f, 200f, 200f, 120f, 16f, 80f, 128f)
        assertEquals(80f, short[2].y)
    }
}
