package id.homebase.core.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FloatingPaneContainerExitTest {

    private val desktop = Size(1200f, 900f)
    private val body = "paneBody"

    private fun SkikoComposeUiTest.showPane(
        confirmsClose: Boolean = false,
        contentTakesClose: Boolean = false,
        onDismiss: () -> Unit,
    ) {
        setContent {
            MaterialTheme {
                val pane: @Composable () -> Unit = {
                    val closeGuard = LocalPaneCloseGuard.current
                    SideEffect { closeGuard?.value = { contentTakesClose } }
                    Box(Modifier.testTag(body))
                }
                FloatingPaneContainer(
                    onDismiss = onDismiss,
                    paneContent = pane,
                    content = pane,
                    confirmsClose = confirmsClose,
                )
            }
        }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.pressEsc() {
        onNodeWithTag(body).performKeyInput { pressKey(Key.Escape) }
    }

    @Test
    fun `escape runs the exit before calling onDismiss once`() =
        runSkikoComposeUiTest(size = desktop) {
            var dismissals = 0
            showPane { dismissals++ }

            mainClock.autoAdvance = false
            pressEsc()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(30)

            onNodeWithTag(body).assertExists()
            assertEquals(0, dismissals)

            mainClock.autoAdvance = true
            waitForIdle()

            assertEquals(1, dismissals)
        }

    @Test
    fun `double escape dismisses once`() =
        runSkikoComposeUiTest(size = desktop) {
            var dismissals = 0
            showPane { dismissals++ }

            onNodeWithTag(body).performKeyInput { pressKey(Key.Escape); pressKey(Key.Escape) }
            waitForIdle()

            assertEquals(1, dismissals)
        }

    @Test
    fun `escape leaves the pane open when its content takes the close`() =
        runSkikoComposeUiTest(size = desktop) {
            var dismissals = 0
            showPane(contentTakesClose = true) { dismissals++ }

            pressEsc()
            waitForIdle()

            assertEquals(0, dismissals)
            onNodeWithTag(body).assertExists()
        }

    @Test
    fun `a click outside a pane that confirms close goes through its content`() =
        runSkikoComposeUiTest(size = desktop) {
            var dismissals = 0
            showPane(confirmsClose = true, contentTakesClose = true) { dismissals++ }

            onRoot().performTouchInput { click(Offset(5f, 5f)) }
            waitForIdle()

            assertEquals(0, dismissals)
        }

    @Test
    fun `a click outside a pane that confirms close dismisses when the content lets it`() =
        runSkikoComposeUiTest(size = desktop) {
            var dismissals = 0
            showPane(confirmsClose = true) { dismissals++ }

            onRoot().performTouchInput { click(Offset(5f, 5f)) }
            waitForIdle()

            assertEquals(1, dismissals)
        }

    @Test
    fun `a click inside a pane that confirms close leaves it open`() =
        runSkikoComposeUiTest(size = desktop) {
            var dismissals = 0
            showPane(confirmsClose = true) { dismissals++ }

            onRoot().performTouchInput { click(center) }
            waitForIdle()

            assertEquals(0, dismissals)
        }
}
