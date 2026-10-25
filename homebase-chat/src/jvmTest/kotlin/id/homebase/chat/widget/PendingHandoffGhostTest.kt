@file:OptIn(ExperimentalTestApi::class)

package id.homebase.chat.widget

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
import androidx.compose.runtime.snapshotFlow
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The pending placeholder and its real message share a key; ConversationContent gives each its own
// contentType and animateItem wrapper. Mirrors that, plus the own-send follow and KeepListEndInView.
class PendingHandoffGhostTest {

    private sealed interface Item { val key: String }
    private data class Msg(override val key: String, val height: Int, val mine: Boolean, val color: Color) : Item
    private data class Pending(override val key: String) : Item

    private enum class Handoff { SameFrame, KeyAbsentForAFewFrames }

    private class Outcome(
        val placeholderPixels: Int,
        val placeholderPixelsAfterScroll: Int,
        val stranded: Int,
        val laidOutKeysNotInList: Int,
    )

    private val placeholderColor = Color(0xFF0000FF)
    private val history: List<Item> = List(30) { i -> Msg("m$i", 60 + (i * 37) % 140, i % 3 == 0, Color.Red) }

    @Composable
    private fun MessageList(rows: List<Item>, listState: LazyListState, sharedWrapper: Boolean) {
        var animationsEnabled by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            animationsEnabled = true
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(rows, key = { it.key }, contentType = { if (it is Pending) "pending" else "message" }) { item ->
                val animate = if (animationsEnabled) Modifier.animateItem() else Modifier
                if (sharedWrapper) {
                    Box(animate) { Row(item) }
                } else {
                    when (item) {
                        is Pending -> Box(animate) { Row(item) }
                        is Msg -> Box(animate) { Row(item) }
                    }
                }
            }
        }
    }

    @Composable
    private fun Row(item: Item) {
        when (item) {
            is Pending -> Box(Modifier.fillMaxWidth().height(120.dp).background(placeholderColor))
            is Msg -> Box(Modifier.fillMaxWidth().height(item.height.dp)) {
                Box(
                    Modifier.align(if (item.mine) Alignment.CenterEnd else Alignment.CenterStart)
                        .fillMaxWidth(0.6f).fillMaxHeight(0.8f).background(item.color),
                )
            }
        }
    }

    @Composable
    private fun OwnSendFollow(listState: LazyListState) {
        LaunchedEffect(listState) {
            var previous = 0
            snapshotFlow { listState.layoutInfo.totalItemsCount }.collect { total ->
                if (previous in 1 until total) listState.animateScrollToItem(total - 1)
                previous = total
            }
        }
    }

    private fun ComposeUiTest.placeholderPixels(): Int {
        val pixels = onRoot().captureToImage().toPixelMap()
        var count = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val c = pixels[x, y]
            if (c.blue - maxOf(c.red, c.green) > 0.15f) count++
        }
        return count
    }

    private fun ComposeUiTest.sendImage(handoff: Handoff, sharedWrapper: Boolean = false): Outcome {
        var rows by mutableStateOf(history)
        val listState = LazyListState(firstVisibleItemIndex = Int.MAX_VALUE)
        setContent {
            Box(Modifier.size(360.dp, 720.dp).background(Color.White)) {
                MessageList(rows, listState, sharedWrapper)
                OwnSendFollow(listState)
                KeepListEndInView(listState, Unit)
            }
        }
        waitForIdle()

        rows = history + Pending("sent")
        waitForIdle()
        mainClock.advanceTimeBy(2_000)
        assertTrue(placeholderPixels() > 0, "placeholder not on screen")

        mainClock.autoAdvance = false
        val landed = Msg("sent", 120, true, Color.Green)
        if (handoff == Handoff.KeyAbsentForAFewFrames) {
            rows = history
            repeat(3) { mainClock.advanceTimeByFrame() }
        }
        rows = history + landed
        repeat(2) { mainClock.advanceTimeByFrame() }
        // The image decodes and the bubble grows in place.
        rows = history + landed.copy(height = 320)
        repeat(4) { mainClock.advanceTimeByFrame() }
        mainClock.autoAdvance = true
        waitForIdle()
        mainClock.advanceTimeBy(3_000)
        waitForIdle()

        val settled = placeholderPixels()
        val stranded = strandedDisappearingItems()
        val listKeys = rows.mapTo(HashSet()) { it.key }
        val laidOutKeysNotInList = listState.layoutInfo.visibleItemsInfo.count { it.key !in listKeys }
        runOnUiThread { listState.dispatchRawDelta(-200f) }
        waitForIdle()
        return Outcome(settled, placeholderPixels(), stranded, laidOutKeysNotInList)
    }

    @Test
    fun aSameFrameContentTypeSwitchUnderOneKeyLeavesNothingBehind() = runComposeUiTest {
        val outcome = sendImage(Handoff.SameFrame)
        assertEquals(0, outcome.stranded)
        assertEquals(0, outcome.placeholderPixels)
        assertEquals(0, outcome.placeholderPixelsAfterScroll)
    }

    @Test
    fun oneSharedAnimateItemWrapperChangesNothingForASameFrameSwitch() = runComposeUiTest {
        val outcome = sendImage(Handoff.SameFrame, sharedWrapper = true)
        assertEquals(0, outcome.stranded)
        assertEquals(0, outcome.placeholderPixels)
    }

    // Characterizes current (buggy) Compose behaviour, not a contract: the returning key takes over the
    // fading animation, whose layer the follow scroll releases while it is still in the disappearing list.
    // Skiko paints nothing for it, and layoutInfo cannot see it, so GhostProbe on a device cannot either.
    @Test
    fun currentlyAKeyThatReturnsDuringItsFadeOutStrandsAnAnimatorEntry() = runComposeUiTest {
        val outcome = sendImage(Handoff.KeyAbsentForAFewFrames)
        assertEquals(1, outcome.stranded, "Compose no longer strands the entry; revisit #1865")
        assertEquals(0, outcome.laidOutKeysNotInList)
        assertEquals(0, outcome.placeholderPixels)
        assertEquals(0, outcome.placeholderPixelsAfterScroll)
    }
}
