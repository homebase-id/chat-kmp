package id.homebase.chat.groupsettings

import androidx.compose.material3.MaterialTheme
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
class GroupSettingsHealSheetDismissTest {

    private val compactPhone = Size(400f, 800f)

    private val title = "Healing group"

    private fun SkikoComposeUiTest.showSheet(finished: Boolean, onSheetClosed: () -> Unit) {
        setContent {
            MaterialTheme {
                GroupSettingsSheets(
                    uiState = GroupSettingsUiState(
                        isLoading = false,
                        uiSheet = GroupSettingsUiSheet.HealProgress(
                            items = emptyList(),
                            finished = finished,
                        ),
                    ),
                    onUiAction = {},
                    onSheetClosed = onSheetClosed,
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
    fun `a tap outside mid-heal leaves the sheet on screen`() =
        runSkikoComposeUiTest(size = compactPhone) {
            var closes = 0
            showSheet(finished = false) { closes++ }

            onNodeWithText(title).assertIsDisplayed()
            tapAboveTheSheet()

            onNodeWithText(title).assertIsDisplayed()
            assertEquals(0, closes)
        }

    @Test
    fun `a tap outside once the heal finished dismisses`() =
        runSkikoComposeUiTest(size = compactPhone) {
            var closes = 0
            showSheet(finished = true) { closes++ }

            tapAboveTheSheet()

            assertEquals(1, closes)
        }
}
