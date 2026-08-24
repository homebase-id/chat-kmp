package id.homebase.core.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SettingsOptionRowTest {

    @Test
    fun reportsSelectionInsteadOfLeavingItToTheCheckMark() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsOptionRow(label = "Dark", selected = true, onClick = {})
                SettingsOptionRow(label = "Light", selected = false, onClick = {})
            }
        }
        onNodeWithText("Dark").assertIsSelected()
        onNodeWithText("Light").assertIsNotSelected()
    }

    @Test
    fun firesOnClick() = runComposeUiTest {
        var clicked = ""
        setContent {
            MaterialTheme {
                SettingsOptionRow(label = "Light", selected = false, onClick = { clicked = "Light" })
            }
        }
        onNodeWithText("Light").performClick()
        assertEquals("Light", clicked)
    }

    /** A single choice out of many is a radio button, not a switch. */
    @Test
    fun isNotToggleable() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsOptionRow(label = "Dark", selected = true, onClick = {})
            }
        }
        onAllNodes(isToggleable()).assertCountEquals(0)
    }
}
