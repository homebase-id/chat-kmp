package id.homebase.chat.widget

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val GAP = 4
private val WINDOW = IntSize(1080, 1920)
private val BAR = IntSize(700, 120)

class AboveBubblePositionProviderTest {

    private fun position(
        anchor: IntRect,
        alignToEnd: Boolean,
        window: IntSize = WINDOW,
        bar: IntSize = BAR,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) = AboveBubblePositionProvider(GAP, alignToEnd)
        .calculatePosition(anchor, window, layoutDirection, bar)

    @Test
    fun `sits above the bubble with a gap`() {
        val anchor = IntRect(300, 800, 1000, 950)
        assertEquals(800 - BAR.height - GAP, position(anchor, alignToEnd = true).y)
    }

    @Test
    fun `flips below when the bubble is near the top of the viewport`() {
        val anchor = IntRect(300, 10, 1000, 160)
        assertEquals(160 + GAP, position(anchor, alignToEnd = true).y)
    }

    @Test
    fun `a sent bubble aligns the bar to the bubble's right edge in LTR`() {
        val anchor = IntRect(300, 800, 1000, 950)
        assertEquals(1000 - BAR.width, position(anchor, alignToEnd = true).x)
    }

    @Test
    fun `a received bubble aligns the bar to the bubble's left edge in LTR`() {
        val anchor = IntRect(300, 800, 1000, 950)
        assertEquals(300, position(anchor, alignToEnd = false).x)
    }

    @Test
    fun `end alignment mirrors in RTL`() {
        val anchor = IntRect(80, 800, 780, 950)
        assertEquals(80, position(anchor, alignToEnd = true, layoutDirection = LayoutDirection.Rtl).x)
    }

    @Test
    fun `a bar wider than the bubble is clamped inside the window`() {
        val narrowWindow = IntSize(720, 1600)
        val anchor = IntRect(500, 800, 700, 950)
        val x = position(anchor, alignToEnd = true, window = narrowWindow).x
        assertTrue(x >= 0, "x=$x ran off the start edge")
        assertTrue(x + BAR.width <= narrowWindow.width, "x=$x ran off the end edge")
    }

    @Test
    fun `a bar wider than the window still starts on screen`() {
        val tinyWindow = IntSize(400, 1600)
        val anchor = IntRect(20, 800, 380, 950)
        assertEquals(0, position(anchor, alignToEnd = true, window = tinyWindow).x)
    }

    @Test
    fun `without flipping, a card with no room above is pinned inside the window margin`() {
        val margin = 8
        val card = IntSize(360, 440)
        val anchor = IntRect(0, 300, 48, 348)
        val offset = AboveBubblePositionProvider(GAP, alignToEnd = false, windowMarginPx = margin, flipBelow = false)
            .calculatePosition(anchor, WINDOW, LayoutDirection.Ltr, card)
        assertEquals(margin, offset.x)
        assertEquals(margin, offset.y)
        val rtl = AboveBubblePositionProvider(GAP, alignToEnd = false, windowMarginPx = margin, flipBelow = false)
            .calculatePosition(IntRect(1060, 1800, 1080, 1848), WINDOW, LayoutDirection.Rtl, card)
        assertEquals(WINDOW.width - card.width - margin, rtl.x)
        assertEquals(1800 - card.height - GAP, rtl.y)
    }

    @Test
    fun `flipping below a bubble at the bottom stays inside the window`() {
        val anchor = IntRect(300, 0, 1000, 1900)
        val y = position(anchor, alignToEnd = true).y
        assertTrue(y + BAR.height <= WINDOW.height, "y=$y ran off the bottom")
    }
}
