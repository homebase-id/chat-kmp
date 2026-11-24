package id.homebase.photobase.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import id.homebase.core.haptics.HapticEvent
import id.homebase.core.haptics.Haptics
import id.homebase.photobase.gallery.GalleryLayout
import id.homebase.photobase.gallery.GalleryLines
import id.homebase.photobase.gallery.LibraryDays
import id.homebase.photobase.gallery.PhotoGalleryItem
import androidx.compose.ui.test.ComposeUiTest
import id.homebase.core.util.formatMonthYear
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalSharedTransitionApi::class)
class PhotoScrubberTest {
    // Ten years, a day every three days, ten photos each: none read, so every tile is a placeholder.
    private val newest = LocalDate(2024, 12, 31).toEpochDays().toInt()
    private val library = LibraryDays(IntArray(1_200) { newest - it * 3 }, IntArray(1_200) { 10 })
    private val layout = GalleryLayout(library, newestFirst = true)

    // 400 dp wide, less 4 dp padding each side, fits four 96 dp columns with 2 dp gaps.
    private val lines = GalleryLines(layout, columns = 4)

    private fun ComposeUiTest.showGrid(gridState: LazyListState, haptics: Haptics) = setContent {
        SharedTransitionLayout {
            AnimatedVisibility(visible = true) {
                Box(Modifier.size(400.dp, 800.dp)) {
                    PhotoGrid(layout, mutableStateOf(emptyMap<Int, List<PhotoGalleryItem>>()), {}, GridMemory(gridState), this@SharedTransitionLayout, this@AnimatedVisibility, haptics)
                }
            }
        }
    }

    private val HANDLE = "Scroll through your photos by date"

    private class CountingHaptics : Haptics {
        var ticks = 0
        override fun perform(event: HapticEvent) {
            if (event == HapticEvent.Selection) ticks++
        }
    }

    @Test
    fun aMouseNearTheEdgeShowsTheHandleAndDraggingItMovesTheGrid() = runComposeUiTest {
        val gridState = LazyListState()
        showGrid(gridState, CountingHaptics())
        waitForIdle()
        val handle = onNodeWithContentDescription(HANDLE)
        handle.assertDoesNotExist()

        onRoot().performMouseInput { moveTo(Offset(width - 10f, height / 2f)) }
        waitForIdle()
        handle.assertExists()

        handle.performMouseInput {
            moveTo(center)
            press()
            repeat(20) { moveBy(Offset(0f, 15f)) }
            release()
        }
        waitForIdle()
        assertTrue(gridState.firstVisibleItemIndex > 1_000, "the mouse drags it like a finger: ${gridState.firstVisibleItemIndex}")
    }

    @Test
    fun rightToLeftTheMouseShowsTheHandleOnTheLeftEdge() = runComposeUiTest {
        val gridState = LazyListState()
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                SharedTransitionLayout {
                    AnimatedVisibility(visible = true) {
                        Box(Modifier.size(400.dp, 800.dp)) {
                            PhotoGrid(layout, mutableStateOf(emptyMap<Int, List<PhotoGalleryItem>>()), {}, GridMemory(gridState), this@SharedTransitionLayout, this@AnimatedVisibility, CountingHaptics())
                        }
                    }
                }
            }
        }
        waitForIdle()
        onRoot().performMouseInput { moveTo(Offset(width - 10f, height / 2f)) }
        waitForIdle()
        onNodeWithContentDescription(HANDLE).assertDoesNotExist()

        onRoot().performMouseInput { moveTo(Offset(10f, height / 2f)) }
        waitForIdle()
        onNodeWithContentDescription(HANDLE).assertExists()
    }

    @Test
    fun draggingTheHandleMovesTheGridInProportionAndSaysWhichMonthItsIn() = runComposeUiTest {
        val gridState = LazyListState()
        val haptics = CountingHaptics()
        showGrid(gridState, haptics)
        waitForIdle()
        // The handle shows once the grid scrolls.
        onRoot().performTouchInput { swipeUp() }
        waitForIdle()
        val before = gridState.firstVisibleItemIndex
        val handle = onNodeWithContentDescription(HANDLE)

        handle.performTouchInput {
            down(center)
            moveBy(Offset(0f, 1f))
        }
        waitForIdle()
        assertTrue(abs(gridState.firstVisibleItemIndex - before) < 20, "grabbing the handle doesn't move the grid")
        assertEquals(0, haptics.ticks, "no tick for the month it was grabbed in")

        handle.performTouchInput {
            repeat(20) { moveBy(Offset(0f, 15f)) }
        }
        waitForIdle()
        val day = LocalDate.fromEpochDays(lines.dayOfItem(gridState.firstVisibleItemIndex))
        val bubble = onNodeWithText(formatMonthYear(LocalDate(day.year, day.month, 1).atStartOfDayIn(TimeZone.currentSystemDefault())))
        bubble.assertExists()
        assertTrue(bubble.fetchSemanticsNode().size.width > with(density) { 60.dp.toPx() }, "the label on one line, not squeezed into the 48 dp track")
        assertTrue(haptics.ticks > 0, "a tick for each month passed")
        val fraction = gridState.firstVisibleItemIndex.toFloat() / lines.itemCount
        handle.performTouchInput { up() }
        waitForIdle()
        assertTrue(fraction > 0.2f && fraction < 0.8f, "300 px of an 800 px track lands well inside the library: $fraction")
        assertTrue(abs(gridState.firstVisibleItemIndex - before) > 1_000, "and far from where it started")
    }
}
