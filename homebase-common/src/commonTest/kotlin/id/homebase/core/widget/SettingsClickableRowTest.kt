package id.homebase.core.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsClickableRowTest {

    private val options = persistentListOf(
        SettingsRowItemData("English", "en"),
        SettingsRowItemData("Dutch", "nl"),
        SettingsRowItemData("German", "de"),
    )

    @Test
    fun displaysLabelAndSelectedValue() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsClickableRow(
                    label = "Language",
                    selectedValue = options[0],
                    options = options,
                    onSelected = {},
                )
            }
        }
        onNodeWithText("Language").assertExists()
        onNodeWithText("English").assertExists()
    }

    @Test
    fun expandsOptionsOnClick() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsClickableRow(
                    label = "Language",
                    selectedValue = options[0],
                    options = options,
                    onSelected = {},
                )
            }
        }
        // Options should not be visible initially
        onNodeWithText("Dutch").assertDoesNotExist()

        // Click the row to expand
        onNodeWithText("Language").performClick()

        // Options should now be visible
        onNodeWithText("Dutch").assertExists()
        onNodeWithText("German").assertExists()
    }

    @Test
    fun selectingOptionFiresCallback() = runComposeUiTest {
        var selected = ""
        setContent {
            MaterialTheme {
                SettingsClickableRow(
                    label = "Language",
                    selectedValue = options[0],
                    options = options,
                    onSelected = { selected = it },
                )
            }
        }
        // Expand
        onNodeWithText("Language").performClick()
        // Select Dutch
        onNodeWithText("Dutch").performClick()
        assertEquals("nl", selected)
    }
}
