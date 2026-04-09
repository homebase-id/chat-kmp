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
class StyledSearchTextFieldTest {

    @Test
    fun showsPlaceholderText() = runComposeUiTest {
        setContent {
            MaterialTheme {
                StyledSearchTextField(
                    textFieldState = TextFieldState(),
                    placeHolderText = "Search conversations...",
                )
            }
        }
        onNodeWithText("Search conversations...").assertExists()
    }

    @Test
    fun showsSearchIcon_byDefault() = runComposeUiTest {
        setContent {
            MaterialTheme {
                StyledSearchTextField(
                    textFieldState = TextFieldState(),
                    placeHolderText = "Search",
                )
            }
        }
        onNodeWithTag("search_icon", useUnmergedTree = true).assertExists()
    }

    @Test
    fun showsBackButton_whenEnabled() = runComposeUiTest {
        var backClicked = false
        setContent {
            MaterialTheme {
                StyledSearchTextField(
                    textFieldState = TextFieldState(),
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
                StyledSearchTextField(
                    textFieldState = TextFieldState(),
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
                StyledSearchTextField(
                    textFieldState = TextFieldState(initialText = "hello"),
                    placeHolderText = "Search",
                )
            }
        }
        onNodeWithTag("clear_input_button").assertExists()
    }
}
