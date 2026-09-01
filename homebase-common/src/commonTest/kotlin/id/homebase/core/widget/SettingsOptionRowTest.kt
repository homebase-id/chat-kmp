package id.homebase.core.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
    fun reportsSelectionOnTheRow() = runComposeUiTest {
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

    /** The announced role has to match the control that is actually rendered. */
    @Test
    fun announcesTheRadioButtonRole() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsOptionRow(label = "Dark", selected = true, onClick = {})
            }
        }
        onNodeWithText("Dark")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
    }

    @Test
    fun rendersSupportingTextWhenGiven() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsOptionRow(
                    label = "High",
                    selected = false,
                    onClick = {},
                    supportingText = "Photos exactly as they are",
                )
            }
        }
        onNodeWithText("Photos exactly as they are").assertIsDisplayed()
    }
}
