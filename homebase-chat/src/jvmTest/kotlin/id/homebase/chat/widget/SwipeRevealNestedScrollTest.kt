package id.homebase.chat.widget

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class SwipeRevealNestedScrollTest {

    private val scrollerTag = "scroller"

    @Composable
    private fun Harness(scroll: ScrollState, onSwipeRight: () -> Unit, rtl: Boolean = false) {
        WithComposerPreferences {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    SwipeRevealBox(
                        onSwipeRight = onSwipeRight,
                        onSwipeLeft = null,
                        commitThreshold = SwipeDistance.Fixed(20.dp),
                        maxOffset = SwipeDistance.Fixed(40.dp),
                        enabled = true,
                        modifier = Modifier.fillMaxWidth(),
                        reveal = {},
                    ) {
                        Box(Modifier.testTag(scrollerTag).fillMaxWidth().horizontalScroll(scroll)) {
                            Box(Modifier.width(3000.dp).height(64.dp))
                        }
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.swipe(dx: Float) {
        onNodeWithTag(scrollerTag).performTouchInput {
            down(centerLeft + Offset(if (dx > 0f) 100f else 700f, 0f))
            moveBy(Offset(dx / 2, 0f))
            moveBy(Offset(dx / 2, 0f))
            up()
        }
        waitForIdle()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
    }

    @Test
    fun aRightSwipeAtTheStartEdgeHandsOffToReply() = runComposeUiTest {
        var fired = 0
        val scroll = ScrollState(0)
        setContent { Harness(scroll, onSwipeRight = { fired++ }) }
        waitForIdle()

        swipe(120f)

        assertEquals(1, fired)
        assertEquals(0, scroll.value)
    }

    @Test
    fun aRightSwipeMidScrollScrollsBackAndDoesNotReply() = runComposeUiTest {
        var fired = 0
        val scroll = ScrollState(0)
        setContent { Harness(scroll, onSwipeRight = { fired++ }) }
        waitForIdle()
        runBlocking { scroll.scrollTo(500) }
        waitForIdle()

        swipe(120f)

        assertEquals(0, fired)
        assertTrue(scroll.value < 500, "the code should have scrolled back, was ${scroll.value}")
    }

    @Test
    fun aSwipeThatScrollsToTheEdgeAndKeepsGoingDoesNotReply() = runComposeUiTest {
        var fired = 0
        val scroll = ScrollState(0)
        setContent { Harness(scroll, onSwipeRight = { fired++ }) }
        waitForIdle()
        runBlocking { scroll.scrollTo(50) }
        waitForIdle()

        swipe(200f)

        assertEquals(0, fired)
        assertEquals(0, scroll.value)
    }

    @Test
    fun aLeftSwipeAtTheStartEdgeScrollsForwardWithoutReplying() = runComposeUiTest {
        var fired = 0
        val scroll = ScrollState(0)
        setContent { Harness(scroll, onSwipeRight = { fired++ }) }
        waitForIdle()

        swipe(-120f)

        assertEquals(0, fired)
        assertTrue(scroll.value > 0)
    }

    @Test
    fun underRtlTheReplyHandsOffOnlyAtTheEdgeThatCannotScrollRight() = runComposeUiTest {
        var fired = 0
        val scroll = ScrollState(0)
        setContent { Harness(scroll, onSwipeRight = { fired++ }, rtl = true) }
        waitForIdle()

        swipe(120f)
        assertEquals(0, fired, "at the RTL start edge a right drag scrolls the content")
        assertTrue(scroll.value > 0)

        runBlocking { scroll.scrollTo(scroll.maxValue) }
        waitForIdle()
        swipe(120f)
        assertEquals(1, fired, "at the far edge a right drag has nothing left to scroll")
    }
}
