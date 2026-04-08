package id.homebase.core.widget

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
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
        onNodeWithContentDescription("Search").assertExists()
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
        onNodeWithContentDescription("Back").performClick()
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
        onNodeWithContentDescription("Clear input").assertDoesNotExist()
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
        onNodeWithContentDescription("Clear input").assertExists()
    }
}
