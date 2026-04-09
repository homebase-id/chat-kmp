package id.homebase.core.widget

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MinimalTextFieldTest {

    @Test
    fun showsPlaceholder() = runComposeUiTest {
        setContent {
            MaterialTheme {
                MinimalTextField(
                    state = TextFieldState(),
                    placeHolderText = "Enter name...",
                )
            }
        }
        onNodeWithText("Enter name...").assertExists()
    }

    @Test
    fun hidesBackButton_byDefault() = runComposeUiTest {
        setContent {
            MaterialTheme {
                MinimalTextField(
                    state = TextFieldState(),
                    placeHolderText = "Search",
                )
            }
        }
        onNodeWithTag("back_button").assertDoesNotExist()
    }

    @Test
    fun showsBackButton_whenEnabled() = runComposeUiTest {
        var backClicked = false
        setContent {
            MaterialTheme {
                MinimalTextField(
                    state = TextFieldState(),
                    showBackButton = true,
                    placeHolderText = "Search",
                    onBackButtonClick = { backClicked = true },
                )
            }
        }
        onNodeWithTag("back_button").performClick()
        assertTrue(backClicked)
    }

    @Test
    fun hidesClearButton_whenEmpty() = runComposeUiTest {
        setContent {
            MaterialTheme {
                MinimalTextField(
                    state = TextFieldState(),
                    placeHolderText = "Search",
                )
            }
        }
        onNodeWithTag("clear_input_button").assertDoesNotExist()
    }

    @Test
    fun showsClearButton_whenTextPresent() = runComposeUiTest {
        setContent {
            MaterialTheme {
                MinimalTextField(
                    state = TextFieldState(initialText = "hello"),
                    placeHolderText = "Search",
                )
            }
        }
        onNodeWithTag("clear_input_button").assertExists()
    }
}
