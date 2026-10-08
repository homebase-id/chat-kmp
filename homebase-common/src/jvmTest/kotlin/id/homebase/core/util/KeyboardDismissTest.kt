package id.homebase.core.util

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class KeyboardDismissTest {

    private class Probe {
        var focused = false
    }

    private fun ComposeUiTest.render(probe: Probe, rowTap: (() -> Unit)? = null) = setContent {
        var text by remember { mutableStateOf("") }
        var swiped by remember { mutableStateOf(0f) }
        Column(Modifier.fillMaxSize()) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.testTag("field").height(40.dp).fillMaxWidth()
                    .onFocusChanged { probe.focused = it.isFocused },
            )
            LazyColumn(Modifier.testTag("list").weight(1f).fillMaxWidth().dismissKeyboardOnTap()) {
                items((0 until 60).toList()) { i ->
                    Box(
                        Modifier.testTag("row$i").fillMaxWidth().height(60.dp)
                            .pointerInput(Unit) { detectHorizontalDragGestures { _, d -> swiped += d } }
                            .pointerInput(rowTap) { detectTapGestures(onTap = { rowTap?.invoke() }) },
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.focusField(probe: Probe) {
        onNodeWithTag("field").performTouchInput { click(center) }
        waitForIdle()
        assertTrue(probe.focused, "precondition: field should be focused")
    }

    @Test
    fun tapInList_clearsFocus() = runComposeUiTest {
        val probe = Probe()
        render(probe)
        focusField(probe)
        onNodeWithTag("row3").performTouchInput { click(center) }
        waitForIdle()
        assertFalse(probe.focused)
    }

    @Test
    fun tapConsumedByRowHandler_stillClearsFocus() = runComposeUiTest {
        val probe = Probe()
        var taps = 0
        render(probe) { taps++ }
        focusField(probe)
        onNodeWithTag("row3").performTouchInput { click(center) }
        waitForIdle()
        assertTrue(taps == 1)
        assertFalse(probe.focused)
    }

    @Test
    fun verticalScroll_keepsFocus() = runComposeUiTest {
        val probe = Probe()
        render(probe)
        focusField(probe)
        onNodeWithTag("list").performTouchInput { swipeUp() }
        waitForIdle()
        assertTrue(probe.focused, "scrolling the list must not clear focus")
    }

    @Test
    fun horizontalSwipeOnRow_keepsFocus() = runComposeUiTest {
        val probe = Probe()
        render(probe)
        focusField(probe)
        onNodeWithTag("row3").performTouchInput { swipeLeft() }
        waitForIdle()
        assertTrue(probe.focused, "swiping a row must not clear focus")
    }

    @Test
    fun longPressHeldStill_clearsFocusBeforeLift() = runComposeUiTest {
        val probe = Probe()
        render(probe)
        focusField(probe)
        onNodeWithTag("row3").performTouchInput { down(center) }
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        assertFalse(probe.focused, "a held press must hide the keyboard before the finger lifts")
        onNodeWithTag("row3").performTouchInput { up() }
    }
}
