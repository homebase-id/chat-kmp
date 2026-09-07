package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

// Forced compact size: the wide branch is a plain Dialog and cannot reach the bug, so these pass
// against broken code at the default 1024x768.
@OptIn(ExperimentalTestApi::class)
class GuardedComposerSheetDismissTest {

    private val compactPhone = Size(400f, 800f)

    private val body = "composerBody"

    private val discardTitle = "Discard changes?"

    private fun SkikoComposeUiTest.showSheet(unsaved: Boolean, onDismiss: () -> Unit) {
        setContent {
            MaterialTheme {
                GuardedComposerSheet(onDismiss = onDismiss) { _, reportUnsaved ->
                    LaunchedEffect(unsaved) { reportUnsaved(unsaved) }
                    Box(Modifier.testTag(body).fillMaxWidth().height(300.dp))
                }
            }
        }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.tapAboveTheSheet() {
        onAllNodes(isRoot()).onLast().performTouchInput {
            click(Offset(width / 2f, 4f))
        }
        waitForIdle()
    }

    @Test
    fun `a tap outside with a draft leaves the sheet on screen`() =
        runSkikoComposeUiTest(size = compactPhone) {
            var dismissals = 0
            showSheet(unsaved = true) { dismissals++ }

            onNodeWithTag(body).assertIsDisplayed()
            tapAboveTheSheet()

            onNodeWithTag(body).assertIsDisplayed()
            onNodeWithText(discardTitle).assertDoesNotExist()
            assertEquals(0, dismissals)
        }

    @Test
    fun `a tap outside with an empty composer dismisses`() =
        runSkikoComposeUiTest(size = compactPhone) {
            var dismissals = 0
            showSheet(unsaved = false) { dismissals++ }

            tapAboveTheSheet()

            assertEquals(1, dismissals)
        }
}
