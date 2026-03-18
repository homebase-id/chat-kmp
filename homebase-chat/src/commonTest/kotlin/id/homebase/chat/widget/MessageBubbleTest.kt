package id.homebase.chat.widget

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import id.homebase.core.ui.theme.LightColors
import id.homebase.core.util.applyDefaultStyling
import id.homebase.core.util.applyMarkDownContent
import kotlin.test.Test
import kotlin.test.assertFails

@OptIn(ExperimentalTestApi::class)
class MessageBubbleTest {

    @Test
    fun testMessageBubble() = runComposeUiTest {
        // Declares a mock UI to demonstrate API calls
        //
        // Replace with your own declarations to test the code of your project
        setContent {
            var text by remember { mutableStateOf("Hello") }
            Text(
                text = text,
                modifier = Modifier.testTag("text")
            )
            Button(
                onClick = { text = "Compose" },
                modifier = Modifier.testTag("button")
            ) {
                Text("Click me")
            }
        }

        // Tests the declared UI with assertions and actions of the Compose Multiplatform testing API
        onNodeWithTag("text").assertTextEquals("Hello")
        onNodeWithTag("button").performClick()
        onNodeWithTag("text").assertTextEquals("Compose")
    }

    @Test
    fun testRichTextSetMarkdown() = runComposeUiTest {
        val testContent = "*Bold* _Italic_"
        setContent {
            val textState = remember {
                RichTextState()
                    .applyDefaultStyling(linkColor = LightColors.Primary)
                    .also {
                        it.setMarkdown(testContent)
                    }
            }

            RichText(
                state = textState,
                modifier = Modifier.testTag("richText")
            )
        }

        onNodeWithTag("richText").assertTextEquals("Bold Italic")
    }

    /*
    RichTextState Markdown parser fails for this input, if this test starts failing, perhaps the issue has been fixed in lib
    The issue seems to be any space in start of line after an empty line
     */
    @Test
    fun testRichTextSetMarkdownProblematicCodeShouldThrow() = runComposeUiTest {
        val testContent = """gg

  gg"""
        setContent {
            assertFails("setMarkdown should fail for this input") {
                remember {
                    RichTextState()
                        .applyDefaultStyling(linkColor = LightColors.Primary)
                        .also {
                            it.setMarkdown(testContent)
                        }
                }
            }
        }
    }

    @Test
    fun testRichTextSetMarkdownProblematicShouldBehandled() = runComposeUiTest {
        val testContent = """gg

  gg"""
        setContent {
            val textState = remember {
                RichTextState()
                    .applyDefaultStyling(linkColor = LightColors.Primary)
                    .applyMarkDownContent(testContent)
            }

            RichText(
                state = textState,
                modifier = Modifier.testTag("richText")
            )
        }

        onNodeWithTag("richText").assertTextEquals("gg gg")
    }
}
