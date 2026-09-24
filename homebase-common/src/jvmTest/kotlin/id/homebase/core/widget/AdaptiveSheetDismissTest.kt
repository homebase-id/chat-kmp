package id.homebase.core.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

// Compact size forces the bottom-sheet branch; the wide branch is a plain Dialog with no slide.
@OptIn(ExperimentalTestApi::class)
class AdaptiveSheetDismissTest {

    private val compactPhone = Size(400f, 800f)
    private val body = "sheetBody"
    private val close = "Close"

    private fun SkikoComposeUiTest.showSheet(
        dismissible: Boolean = true,
        onDismiss: () -> Unit,
        closeWith: AdaptiveSheetScope.(hide: () -> Unit) -> Unit = { dismiss() },
    ) {
        var shown by mutableStateOf(true)
        setContent {
            MaterialTheme {
                if (shown) {
                    AdaptiveSheet(
                        onDismiss = { onDismiss(); shown = false },
                        dismissible = dismissible,
                    ) {
                        Box(Modifier.testTag(body).fillMaxWidth().height(300.dp))
                        TextButton(onClick = { closeWith { shown = false } }) { Text(close) }
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `dismiss slides the sheet out before calling onDismiss`() =
        runSkikoComposeUiTest(size = compactPhone) {
            var dismissals = 0
            showSheet(onDismiss = { dismissals++ })

            mainClock.autoAdvance = false
            onNodeWithText(close).performClick()
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(50)

            onNodeWithTag(body).assertExists()
            assertEquals(0, dismissals)

            mainClock.autoAdvance = true
            waitForIdle()

            assertEquals(1, dismissals)
            onNodeWithTag(body).assertDoesNotExist()
        }

    @Test
    fun `dismiss with an action runs it instead of onDismiss`() =
        runSkikoComposeUiTest(size = compactPhone) {
            var dismissals = 0
            var sent = 0
            showSheet(onDismiss = { dismissals++ }, closeWith = { hide -> dismiss { sent++; hide() } })

            onNodeWithText(close).performClick()
            waitForIdle()

            assertEquals(1, sent)
            assertEquals(0, dismissals)
            onNodeWithText(close).assertDoesNotExist()
        }

    @Test
    fun `dismiss still closes a sheet pinned against swipes`() =
        runSkikoComposeUiTest(size = compactPhone) {
            var dismissals = 0
            showSheet(dismissible = false, onDismiss = { dismissals++ })

            onNodeWithText(close).performClick()
            waitForIdle()

            assertEquals(1, dismissals)
            onNodeWithTag(body).assertDoesNotExist()
        }

    @Test
    fun `a repeated dismiss while hiding dismisses once`() =
        runSkikoComposeUiTest(size = compactPhone) {
            var dismissals = 0
            showSheet(onDismiss = { dismissals++ }, closeWith = { dismiss(); dismiss() })

            onNodeWithText(close).performClick()
            waitForIdle()

            assertEquals(1, dismissals)
        }
}
