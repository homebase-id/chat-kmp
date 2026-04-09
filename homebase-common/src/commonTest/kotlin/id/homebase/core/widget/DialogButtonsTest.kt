package id.homebase.core.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DialogButtonsTest {

    @Test
    fun showsPrimaryButton_whenTextProvided() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                DialogButtons(
                    primaryText = "OK",
                    onPrimaryClick = { clicked = true },
                )
            }
        }
        onNodeWithText("OK").assertExists()
        onNodeWithText("OK").performClick()
        assertTrue(clicked)
    }

    @Test
    fun hidesPrimaryButton_whenTextNull() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DialogButtons(
                    primaryText = null,
                    onPrimaryClick = null,
                )
            }
        }
        onNodeWithText("OK").assertDoesNotExist()
    }

    @Test
    fun showsAllThreeButtons() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DialogButtons(
                    primaryText = "Save",
                    onPrimaryClick = {},
                    secondaryText = "Cancel",
                    onSecondaryClick = {},
                    tertiaryText = "Delete",
                    onTertiaryClick = {},
                )
            }
        }
        onNodeWithText("Save").assertExists()
        onNodeWithText("Cancel").assertExists()
        onNodeWithText("Delete").assertExists()
    }

    @Test
    fun secondaryClickCallbackFires() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                DialogButtons(
                    secondaryText = "Cancel",
                    onSecondaryClick = { clicked = true },
                )
            }
        }
        onNodeWithText("Cancel").performClick()
        assertTrue(clicked)
    }

    @Test
    fun verticalLayout_showsAllButtons() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DialogButtons(
                    primaryText = "Save",
                    onPrimaryClick = {},
                    secondaryText = "Cancel",
                    onSecondaryClick = {},
                    showButtonsVertically = true,
                )
            }
        }
        onNodeWithText("Save").assertExists()
        onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun hidesButton_whenOnlyTextProvided_butCallbackNull() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DialogButtons(
                    primaryText = "OK",
                    onPrimaryClick = null,
                )
            }
        }
        onNodeWithText("OK").assertDoesNotExist()
    }
}
