package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ChatMarkdownCodeSurfaceTest {

    @Test
    fun fencedCodeRendersOnAnExplicitSurface() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ChatMarkdown(
                    content = "```kotlin\nval answer = 42\n```",
                    color = Color.White,
                    surface = Color(0xFF1B5E20),
                )
            }
        }
        waitForIdle()
        onNodeWithText("val answer = 42", substring = true).assertIsDisplayed()
    }
}
