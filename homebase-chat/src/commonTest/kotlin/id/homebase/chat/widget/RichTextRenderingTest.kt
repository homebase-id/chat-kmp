package id.homebase.chat.widget

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import id.homebase.core.ui.theme.LightColors
import id.homebase.core.util.applyDefaultStyling
import id.homebase.core.util.applyMarkDownContent
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class RichTextRenderingTest {

    @OptIn(ExperimentalRichTextApi::class)
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
    RichTextState Markdown parser used to fail for this input hence this test, the issue seems to have been fixed now.
    The issue seems to be any space in start of line after an empty line
     */
    @OptIn(ExperimentalRichTextApi::class)
    @Test
    fun testRichTextSetMarkdownProblematicCodeShouldNowWork() = runComposeUiTest {
        val testContent = """gg

  gg"""
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
        onNodeWithTag("richText").assertTextEquals("gg  gg")
    }

    @OptIn(ExperimentalRichTextApi::class)
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

        onNodeWithTag("richText").assertTextEquals("gg  gg")
    }
}
