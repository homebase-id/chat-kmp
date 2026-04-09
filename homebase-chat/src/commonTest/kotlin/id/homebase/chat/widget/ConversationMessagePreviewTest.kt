package id.homebase.chat.widget

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ConversationMessagePreviewTest {

    @Test
    fun displaysMessageText() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ConversationMessagePreview(
                    text = "Hello there!",
                    iconRes = null,
                    isDeleted = false,
                )
            }
        }
        onNodeWithText("Hello there!").assertExists()
    }

    @Test
    fun showsFallbackText_whenEmptyTextAndNoIcon() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ConversationMessagePreview(
                    text = "",
                    iconRes = null,
                    isDeleted = false,
                )
            }
        }
        onNodeWithTag("no_messages_text").assertExists()
    }

    @Test
    fun noFallback_whenEmptyTextButHasIcon() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ConversationMessagePreview(
                    text = "",
                    iconRes = Icons.Filled.Image,
                    isDeleted = false,
                )
            }
        }
        // Should NOT show fallback when there's an icon
        onNodeWithTag("no_messages_text").assertDoesNotExist()
    }

    @Test
    fun rendersDeletedMessage() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ConversationMessagePreview(
                    text = "Deleted message",
                    iconRes = null,
                    isDeleted = true,
                )
            }
        }
        onNodeWithText("Deleted message").assertExists()
    }
}
