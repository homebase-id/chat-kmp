package id.homebase.chat.widget

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.applyMarkDownContent
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ComposerLinkDialogTest {

    @Test
    fun `caret inside a link opens the dialog with save and remove`() = runComposeUiTest {
        val state = RichTextState().applyMarkDownContent("go [here](https://a.test) now")
        state.selection = TextRange(state.annotatedString.text.indexOf("here") + 2)
        setContent {
            HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                RichTextEditorButtons(state = state, enabled = true)
            }
        }

        onNodeWithContentDescription("Insert link").performClick()
        waitForIdle()

        onNodeWithTag(LINK_DIALOG_SAVE_TAG).assertIsDisplayed()
        onNodeWithTag(LINK_DIALOG_REMOVE_TAG).assertIsDisplayed()
        onNodeWithTag(LINK_DIALOG_REMOVE_TAG).performClick()
        waitForIdle()
        assertEquals("go here now", state.toMarkdown())
    }
}
