package id.homebase.core.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runSkikoComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FloatingPaneContainerExitTest {

    private val desktop = Size(1200f, 900f)
    private val body = "paneBody"

    private fun SkikoComposeUiTest.showPane(onDismiss: () -> Unit) {
        setContent {
            MaterialTheme {
                FloatingPaneContainer(
                    onDismiss = onDismiss,
                    paneContent = { Box(Modifier.testTag(body)) },
                    content = { Box(Modifier.testTag(body)) },
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
}
