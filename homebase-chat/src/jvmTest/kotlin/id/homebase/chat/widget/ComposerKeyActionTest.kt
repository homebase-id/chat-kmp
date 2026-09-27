package id.homebase.chat.widget

import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
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

    private fun runComposer(
        arrowUpEditsLastMessage: Boolean = true,
        text: String = "",
        press: KeyInjectionScope.() -> Unit,
        verify: (edits: Int, text: String) -> Unit,
    ) = runComposeUiTest {
        var edits = 0
        lateinit var state: RichTextState
        setContent {
            WithComposerPreferences(arrowUpEditsLastMessage = arrowUpEditsLastMessage) {
                HomebaseTheme {
                    state = rememberRichTextState()
                    MessageTextFieldCompact(
                        focusRequester = remember { FocusRequester() },
                        state = state,
                        mentionTargets = emptyList(),
                        payloadRenderers = emptyList(),
                        recordingData = null,
                        onCancelAttachment = {},
                        editExistingMode = false,
                        showingEmojiSheet = false,
                        onEmojiClick = {},
                        onKeyboardClick = {},
                        onAddAttachmentClick = {},
                        onCameraClick = {},
                        onRecordingStarted = {},
                        onRecordingStopped = {},
                        onRecordingCancelled = {},
                        onRecordingHelp = {},
                        onSendMessage = {},
                        onEditLast = {
                            edits++
                            true
                        },
                        onCancelEdit = {},
                    )
                }
            }
        }

        onNode(hasSetTextAction()).requestFocus()
        if (text.isNotEmpty()) runOnIdle { state.addTextAfterSelection(text) }
        waitForIdle()

        onNode(hasSetTextAction()).performKeyInput(press)
        waitForIdle()

        verify(edits, state.annotatedString.text)
    }

    @Test
    fun arrowUpInAnEmptyComposerEditsTheLastMessage() = runComposer(
        press = { pressKey(Key.DirectionUp) },
    ) { edits, _ -> assertEquals(1, edits) }

    @Test
    fun arrowUpWithTextMovesTheCaretInstead() = runComposer(
        text = "line one",
        press = { pressKey(Key.DirectionUp) },
    ) { edits, _ -> assertEquals(0, edits) }

    @Test
    fun arrowUpDoesNothingWithThePreferenceOff() = runComposer(
        arrowUpEditsLastMessage = false,
        press = { pressKey(Key.DirectionUp) },
    ) { edits, _ -> assertEquals(0, edits) }

    @Test
    fun ctrlUpEditsEvenWithTextAndThePreferenceOff() = runComposer(
        arrowUpEditsLastMessage = false,
        text = "a draft",
        press = { withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionUp) } },
    ) { edits, text ->
        assertEquals(1, edits)
        assertEquals("a draft", text)
    }

    @Test
    fun bareEnterSendsByDefault() = runCaption(
        enterSendsMessage = true,
        press = { pressKey(Key.Enter) },
    ) {
        assertEquals(1, it.sends)
    }

    @Test
    fun bareEnterBreaksTheLineWhenThePreferenceIsOff() = runCaption(
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
