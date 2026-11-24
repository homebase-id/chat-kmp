package id.homebase.core.ui.screens.feed

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import id.homebase.core.ui.navigation.FloatingPaneContainer
import id.homebase.core.ui.theme.HomebaseTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
class PostComposeCloseGuardTest {

    private val systemBack = object : NavigationEventInput() {
        fun press() = dispatchOnBackCompleted()
    }

    private fun ComposeUiTest.show(
        state: PostComposeUiState,
        inPane: Boolean = false,
        onClose: () -> Unit,
    ) = setContent {
        @Suppress("DEPRECATION")
        val dispatcher = LocalNavigationEventDispatcherOwner.current!!.navigationEventDispatcher
        DisposableEffect(dispatcher) {
            dispatcher.addInput(systemBack)
            onDispose { dispatcher.removeInput(systemBack) }
        }
        HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
            val composer: @Composable () -> Unit = {
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
            Surface {
                if (inPane) {
                    FloatingPaneContainer(onDismiss = onClose, paneContent = composer, content = composer, confirmsClose = true)
                } else {
                    composer()
                }
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

        systemBack.press()
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

    @Test
    fun `escape and an outside click on the floating composer ask before discarding`() =
        runDesktopComposeUiTest(1280, 820) {
            var closes = 0
            show(PostComposeUiState(caption = "Second breakfast"), inPane = true) { closes++ }

            onNodeWithText("New post").performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            onNodeWithText("Discard changes?").assertExists()
            assertEquals(0, closes)

            onNodeWithText("Keep editing").performClick()
            waitForIdle()
            onAllNodes(isRoot())[0].performTouchInput { click(Offset(5f, 5f)) }
            waitForIdle()
            onNodeWithText("Discard changes?").assertExists()
            assertEquals(0, closes)
        }
}
