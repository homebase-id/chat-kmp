package id.homebase.core.media

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class MediaPagerArrowKeysTest {

    private fun androidx.compose.ui.test.ComposeUiTest.setViewer(
        direction: LayoutDirection = LayoutDirection.Ltr,
        withTextField: Boolean = false,
        capture: (PagerState) -> Unit,
    ) = setContent {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            val state = rememberPagerState { 3 }
            capture(state)
            val requester = remember { FocusRequester() }
            androidx.compose.runtime.LaunchedEffect(Unit) { requester.requestFocus() }
            Box(Modifier.fillMaxSize().testTag("viewer").pagerArrowKeys(state).focusRequester(requester).focusable()) {
                MediaPager(pageCount = 3, state = state) {
                    if (withTextField && it == 0) {
                        var text by remember { mutableStateOf("") }
                        BasicTextField(text, { text = it }, Modifier.testTag("field"))
                    } else Box(Modifier.fillMaxSize())
                }
            }
        }
    }

    @Test
    fun arrowsPageAndStopAtEnds() = runComposeUiTest {
        lateinit var state: PagerState
        setViewer { state = it }
        val viewer = onNodeWithTag("viewer")
        viewer.performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        assertEquals(0, state.currentPage)
        viewer.performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertEquals(1, state.currentPage)
        viewer.performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionRight) }
        waitForIdle()
        assertEquals(2, state.currentPage)
        viewer.performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        assertEquals(1, state.currentPage)
    }

    @Test
    fun arrowsFollowRtlDirection() = runComposeUiTest {
        lateinit var state: PagerState
        setViewer(LayoutDirection.Rtl) { state = it }
        onNodeWithTag("viewer").performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        assertEquals(1, state.currentPage)
    }

    @Test
    fun focusedTextFieldKeepsArrows() = runComposeUiTest {
        lateinit var state: PagerState
        setViewer(withTextField = true) { state = it }
        onNodeWithTag("field").requestFocus()
        onNodeWithTag("field").performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertEquals(0, state.currentPage)
    }
}
