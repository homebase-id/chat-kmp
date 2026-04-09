package id.homebase.core.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ListItemActionTest {

    @Test
    fun displaysText() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ListItemAction(
                    imageVector = Icons.Filled.Edit,
                    text = "Edit message",
                    onClick = {},
                )
            }
        }
        onNodeWithText("Edit message").assertExists()
    }

    @Test
    fun clickCallbackFires() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                ListItemAction(
                    imageVector = Icons.Filled.Delete,
                    text = "Delete",
                    onClick = { clicked = true },
                )
            }
        }
        onNodeWithText("Delete").performClick()
        assertTrue(clicked)
    }

    @Test
    fun normalIconVariant_displaysText() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ListItemActionNormalIcon(
                    imageVector = Icons.Filled.Edit,
                    text = "Edit",
                    onClick = {},
                )
            }
        }
        onNodeWithText("Edit").assertExists()
    }

    @Test
    fun normalIconVariant_clickCallbackFires() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                ListItemActionNormalIcon(
                    imageVector = Icons.Filled.Edit,
                    text = "Edit",
                    onClick = { clicked = true },
                )
            }
        }
        onNodeWithText("Edit").performClick()
        assertTrue(clicked)
    }
}
