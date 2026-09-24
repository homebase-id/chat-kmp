package id.homebase.core.camera

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewLayoutTest {
    @Test
    fun aFlipStaysDimmedUntilTheNewLensDeliversAFrame() {
        assertEquals(0.6f, previewScrimAlpha(isBound = true, awaitingFirstFrame = true, previewShown = true))
        assertEquals(0f, previewScrimAlpha(isBound = true, awaitingFirstFrame = false, previewShown = true))
        assertEquals(0.6f, previewScrimAlpha(isBound = false, awaitingFirstFrame = false, previewShown = true))
        assertEquals(1f, previewScrimAlpha(isBound = false, awaitingFirstFrame = false, previewShown = false))
    }

    @Test
    fun theZoomPillLeavesAnEvenGapAtEveryDensity() {
        for (density in listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f)) {
            val slot = (48 * density).roundToInt()
            for (pillDp in 36..44) {
                val side = evenlyInsetSide(slot, pillDp * density)
                assertEquals(0, (slot - side) % 2, "density=$density pill=$pillDp")
                assertTrue(abs(side - pillDp * density) <= 1f, "density=$density pill=$pillDp")
            }
        }
    }
}
