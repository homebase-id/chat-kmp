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
        onNodeWithContentDescription("Back").assertDoesNotExist()
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
        onNodeWithContentDescription("Back").performClick()
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
        onNodeWithContentDescription("Clear input").assertDoesNotExist()
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
        onNodeWithContentDescription("Clear input").assertExists()
    }
}
