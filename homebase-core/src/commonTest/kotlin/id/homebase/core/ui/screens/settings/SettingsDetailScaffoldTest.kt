package id.homebase.core.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.core.widget.ProvideSettingsChrome
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SettingsDetailScaffoldTest {
    @Test
    fun showsTitleAndRowsAndBackInvokesOnBack() = runComposeUiTest {
        var backs = 0
        setContent {
            MaterialTheme {
                SettingsDetailScaffold(title = "Feed", onBack = { backs++ }) { Text("a row") }
            }
        }
        onNodeWithText("Feed").assertExists()
        onNodeWithText("a row").assertExists()
        onNode(hasClickAction()).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun embeddedInTheDesktopPaneDropsTheBarButKeepsTheRows() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ProvideSettingsChrome(embedded = true) {
                    SettingsDetailScaffold(title = "Feed", onBack = {}) { Text("a row") }
                }
            }
        }
        onNodeWithText("Feed").assertDoesNotExist()
        onNodeWithText("a row").assertExists()
    }
}
