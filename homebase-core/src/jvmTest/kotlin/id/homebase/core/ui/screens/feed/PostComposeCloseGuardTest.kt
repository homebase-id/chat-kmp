package id.homebase.core.ui.screens.feed

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import id.homebase.core.ui.theme.HomebaseTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class PostComposeCloseGuardTest {

    private val systemBack = object : NavigationEventInput() {
        fun press() = dispatchOnBackCompleted()
    }

    private fun ComposeUiTest.show(state: PostComposeUiState, onClose: () -> Unit) = setContent {
        @Suppress("DEPRECATION")
        val dispatcher = LocalNavigationEventDispatcherOwner.current!!.navigationEventDispatcher
        DisposableEffect(dispatcher) {
            dispatcher.addInput(systemBack)
            onDispose { dispatcher.removeInput(systemBack) }
        }
        HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
            Surface {
                PostComposeContent(
                    state = state,
                    snackbarHostState = remember { SnackbarHostState() },
                    onClose = onClose,
                    onPost = {},
                    onCaptionChange = {},
                    onAddMedia = {},
                    onCamera = {},
                    onRemoveAttachment = {},
                    onSelectChannel = {},
                    onPickPublic = {},
                    onPickCircles = {},
                )
            }
        }
    }

    private fun ComposeUiTest.pressBack() = onNodeWithContentDescription("Back").performClick()

    @Test
    fun `an empty composer closes at once`() = runDesktopComposeUiTest(411, 820) {
        var closes = 0
        show(PostComposeUiState()) { closes++ }

        pressBack()
        waitForIdle()

        assertEquals(1, closes)
        onNodeWithText("Discard changes?").assertDoesNotExist()
    }

    @Test
    fun `a typed caption asks before it is discarded`() = runDesktopComposeUiTest(411, 820) {
        var closes = 0
        show(PostComposeUiState(caption = "Second breakfast")) { closes++ }

        pressBack()
        waitForIdle()
        onNodeWithText("Discard changes?").assertExists()
        assertEquals(0, closes)

        onNodeWithText("Keep editing").performClick()
        waitForIdle()
        onNodeWithText("Discard changes?").assertDoesNotExist()
        assertEquals(0, closes)

        pressBack()
        onNodeWithText("Discard").performClick()
        waitForIdle()
        assertEquals(1, closes)
    }

    @Test
    fun `closing is blocked while the post is sending`() = runDesktopComposeUiTest(411, 820) {
        var closes = 0
        show(PostComposeUiState(caption = "Second breakfast", isPosting = true)) { closes++ }

        pressBack()
        waitForIdle()

        assertEquals(0, closes)
        onNodeWithText("Discard changes?").assertDoesNotExist()
    }

    @Test
    fun `system back with a typed caption asks before it is discarded`() = runDesktopComposeUiTest(411, 820) {
        var closes = 0
        show(PostComposeUiState(caption = "Second breakfast")) { closes++ }

        systemBack.press()
        waitForIdle()

        onNodeWithText("Discard changes?").assertExists()
        assertEquals(0, closes)
    }
}
