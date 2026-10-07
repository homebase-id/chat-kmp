@file:OptIn(ExperimentalTestApi::class)

package id.homebase.chat.widget

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import id.homebase.core.util.ScrollPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A window swap followed by a snap after its first frame releases the outgoing rows' animateItem fade-outs
// but leaves them in the list's disappearing-item draw, so the old rows stay painted.
class MessageListWindowSwapGhostTest {

    private data class Row(val key: String, val height: Int, val mine: Boolean, val color: Color)

    private enum class Trigger {
        Production,

        // The pre-fix LaunchedEffect: the app's dispatcher runs it after the swap frame, the test clock before.
        SnapAfterTheSwapFrame,

        None,
    }

    private class Outcome(val stalePixels: Int, val stranded: Int, val lastVisibleKey: Any?)

    private val pinColor = Color(0xFF0000FF)

    private fun window(prefix: String, count: Int, color: Color, loadingNewer: Boolean): List<Row> = buildList {
        add(Row("loading-older", 56, false, Color.LightGray))
        repeat(count) { i -> add(Row("$prefix-$i", 60 + (i * 37) % 140, i % 3 == 0, color)) }
        if (loadingNewer) add(Row("loading-newer", 56, false, Color.LightGray))
    }

    private val newest = window("new", 60, Color.Red, loadingNewer = false)
    private val pinned = window("pin", 40, pinColor, loadingNewer = true)

    @Composable
    private fun MessageList(rows: List<Row>, listState: LazyListState, animateItems: Boolean) {
        var animationsEnabled by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            animationsEnabled = animateItems
        }
        SharedTransitionLayout {
            AnimatedContent(Unit) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(rows, key = { it.key }, contentType = { "message" }) { row ->
                        Box((if (animationsEnabled) Modifier.animateItem() else Modifier).fillMaxWidth().height(row.height.dp)) {
                            Box(
                                Modifier.align(if (row.mine) Alignment.CenterEnd else Alignment.CenterStart)
                                    .fillMaxWidth(0.6f).fillMaxHeight(0.8f).background(row.color),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Trigger(trigger: Trigger, listState: LazyListState, position: ScrollPosition?) {
        when (trigger) {
            Trigger.Production -> ScrollPositionTrigger(listState, position) { }
            Trigger.SnapAfterTheSwapFrame -> LaunchedEffect(position) {
                if (position?.triggerScroll != true) return@LaunchedEffect
                withFrameNanos { }
                listState.scrollToItem(position.firstVisibleItemIndex, position.firstVisibleItemScrollOffset)
                if (position.animate) listState.animateScrollToItem(position.firstVisibleItemIndex)
            }
            Trigger.None -> Unit
        }
    }

    private fun ComposeUiTest.pinPixels(): Int {
        val pixels = onRoot().captureToImage().toPixelMap()
        var count = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val c = pixels[x, y]
            if (c.blue > 0.9f && c.red < 0.1f && c.green < 0.1f) count++
        }
        return count
    }

    private fun ComposeUiTest.pinJumpThenScrollToLatest(
        trigger: Trigger,
        animateItems: Boolean = true,
        swapKeys: Boolean = true,
    ): Outcome {
        var rows by mutableStateOf(newest)
        var position by mutableStateOf<ScrollPosition?>(null)
        val listState = LazyListState(firstVisibleItemIndex = Int.MAX_VALUE)
        setContent {
            Box(Modifier.size(360.dp, 720.dp).background(Color.White)) {
                MessageList(rows, listState, animateItems)
                Trigger(trigger, listState, position)
            }
        }
        waitForIdle()

        // Banner tap: one emission carries the pin's window and an animated jump to the pin.
        rows = if (swapKeys) pinned else newest
        position = ScrollPosition(20, triggerScroll = true, animate = true)
        waitForIdle()

        // Scroll-to-bottom: one emission carries the newest page and a jump to its last message.
        rows = newest
        position = ScrollPosition(newest.lastIndex, triggerScroll = true)
        waitForIdle()

        return Outcome(pinPixels(), strandedDisappearingItems(), listState.layoutInfo.visibleItemsInfo.last().key)
    }

    @Test
    fun aWindowSwapAndItsJumpLeaveNoRowsOfTheOldWindowOnScreen() = runComposeUiTest {
        val outcome = pinJumpThenScrollToLatest(Trigger.Production)
        assertEquals(0, outcome.stranded, "fade-outs released mid-flight")
        assertEquals(0, outcome.stalePixels, "rows of the pin's window still drawn")
        assertEquals(newest.last().key, outcome.lastVisibleKey)
    }

    @Test
    fun snappingAFrameAfterTheSwapStrandsTheOutgoingRows() = runComposeUiTest {
        val outcome = pinJumpThenScrollToLatest(Trigger.SnapAfterTheSwapFrame)
        assertTrue(outcome.stranded > 0, "no stranded fade-outs")
        assertTrue(outcome.stalePixels > 0, "no stale rows drawn")
        // Not laid-out rows but a stale recording of released layers: one redraw of the list clears them.
        invalidateDisappearingItemsDraw()
        waitForIdle()
        assertEquals(0, pinPixels())
    }

    @Test
    fun withoutAnimateItemALateSnapLeavesNothing() = runComposeUiTest {
        val outcome = pinJumpThenScrollToLatest(Trigger.SnapAfterTheSwapFrame, animateItems = false)
        assertEquals(0, outcome.stranded)
        assertEquals(0, outcome.stalePixels)
    }

    @Test
    fun withoutAKeySwapALateSnapLeavesNothing() = runComposeUiTest {
        assertEquals(0, pinJumpThenScrollToLatest(Trigger.SnapAfterTheSwapFrame, swapKeys = false).stranded)
    }

    @Test
    fun withoutASnapTheFadeOutsRunToTheEnd() = runComposeUiTest {
        val outcome = pinJumpThenScrollToLatest(Trigger.None)
        assertEquals(0, outcome.stranded)
        assertEquals(0, outcome.stalePixels)
    }

    @Test
    fun liveRemovalsAndInsertsStillAnimate() = runComposeUiTest {
        var rows by mutableStateOf(newest)
        val listState = LazyListState(firstVisibleItemIndex = Int.MAX_VALUE)
        setContent {
            Box(Modifier.size(360.dp, 720.dp)) {
                MessageList(rows, listState, animateItems = true)
                Trigger(Trigger.Production, listState, null)
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false

        val visible = listState.layoutInfo.visibleItemsInfo.map { it.key }
        rows = rows.filterNot { it.key == visible[visible.size / 2] }
        repeat(2) { mainClock.advanceTimeByFrame() }
        assertEquals(1, fadingOutItems(), "a deleted row fades out")

        mainClock.advanceTimeBy(2_000)
        rows = rows.dropLast(1) + Row("arrived", 80, true, Color.Green) + rows.last()
        repeat(2) { mainClock.advanceTimeByFrame() }
        assertTrue("arrived" in fadingInKeys(), "a new row fades in")

        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(0, strandedDisappearingItems())
    }
}
