package id.homebase.chat.widget

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import id.homebase.core.ui.theme.HomebaseTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ComposerKeyActionTest {

    private class Harness {
        lateinit var state: RichTextState
        var sends = 0
    }

    private fun runCaption(
        enterSendsMessage: Boolean,
        press: KeyInjectionScope.() -> Unit,
        verify: (Harness) -> Unit,
    ) = runComposeUiTest {
        val harness = Harness()
        setContent {
            WithComposerPreferences(enterSendsMessage) {
                HomebaseTheme {
                    harness.state = rememberRichTextState()
                    MessageTextFieldForAttachment(
                        state = harness.state,
                        onSendMessage = { harness.sends++ },
                    )
                }
            }
        }

        onNodeWithTag(ATTACHMENT_CAPTION_FIELD_TAG).requestFocus()
        runOnIdle { harness.state.addTextAfterSelection("a caption") }
        waitForIdle()

        onNodeWithTag(ATTACHMENT_CAPTION_FIELD_TAG).performKeyInput(press)
        waitForIdle()

        verify(harness)
    }

    @Test
    fun bareEnterBreaksTheLineByDefault() = runCaption(
        enterSendsMessage = false,
        press = { pressKey(Key.Enter) },
    ) {
        assertEquals(0, it.sends, "Enter must not send while the preference is off")
        assertTrue(
            it.state.annotatedString.text.startsWith("a caption"),
            "got: ${it.state.annotatedString.text}",
        )
    }

    @Test
    fun numPadEnterCountsAsEnter() = runCaption(
        enterSendsMessage = true,
        press = { pressKey(Key.NumPadEnter) },
    ) {
        assertEquals(1, it.sends)
    }
}
