package id.homebase.core.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SettingsItemActionTest {

    @Test
    fun displaysText() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsItemAction(
                    imageVector = Icons.Filled.Notifications,
                    text = "Notifications",
                    onClick = {},
                )
            }
        }
        onNodeWithText("Notifications").assertExists()
    }

    @Test
    fun clickCallbackFires() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                SettingsItemAction(
                    imageVector = Icons.Filled.Notifications,
                    text = "Notifications",
                    onClick = { clicked = true },
                )
            }
        }
        onNodeWithText("Notifications").performClick()
        assertTrue(clicked)
    }

    @Test
    fun showsTrailingContent_whenProvided() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsItemAction(
                    imageVector = Icons.Filled.Notifications,
                    text = "Notifications",
                    onClick = {},
                    trailingContent = { Text("ON") },
                )
            }
        }
        onNodeWithText("Notifications").assertExists()
        onNodeWithText("ON").assertExists()
    }

    @Test
    fun hidesTrailingContent_whenNull() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SettingsItemAction(
                    imageVector = Icons.Filled.Notifications,
                    text = "Notifications",
                    onClick = {},
                    trailingContent = null,
                )
            }
        }
        onNodeWithText("ON").assertDoesNotExist()
    }
}
