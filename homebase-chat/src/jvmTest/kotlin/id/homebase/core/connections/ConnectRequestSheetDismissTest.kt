package id.homebase.core.connections

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Forced compact size: the wide branch is a plain Dialog and cannot reach the bug, so these pass
// against broken code at the default 1024x768.
@OptIn(ExperimentalTestApi::class)
class ConnectRequestSheetDismissTest {

    private val compactPhone = Size(400f, 800f)

    private val title = "New connection request"

    private fun SkikoComposeUiTest.showSheet(
        isSending: Boolean,
        onAction: (ConnectRequestAction) -> Unit,
    ) {
        setContent {
            MaterialTheme {
                ConnectRequestSheet(
                    state = ConnectRequestState(
                        showDialog = true,
                        recipient = "ada.example.com",
                        isSending = isSending,
                    ),
                    sheetSnackbarHostState = SnackbarHostState(),
                    onAction = onAction,
                )
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
    fun `a tap outside mid-send leaves the sheet on screen`() =
        runSkikoComposeUiTest(size = compactPhone) {
            var closes = 0
            showSheet(isSending = true) { if (it is ConnectRequestAction.CloseDialog) closes++ }

            onNodeWithText(title).assertIsDisplayed()
            tapAboveTheSheet()

            onNodeWithText(title).assertIsDisplayed()
            assertEquals(0, closes)
        }

    @Test
    fun `a tap outside while idle dismisses`() =
        runSkikoComposeUiTest(size = compactPhone) {
            var closes = 0
            showSheet(isSending = false) { if (it is ConnectRequestAction.CloseDialog) closes++ }

            tapAboveTheSheet()

            assertEquals(1, closes)
        }
}
