package id.homebase.chat.widget

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
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

/**
 * End-to-end wiring for the `:shortcode` typeahead in a real composer, against the shipped emoji
 * index. Runs on the JVM, where `isDesktopOrWeb()` is true; the mobile branch is covered by
 * `ComposerAutocompleteTest`, which drives the gate directly.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerEmojiTypeaheadTest {

    @Test
    fun typingAShortcodePrefixOffersTheMatchingEmoji() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent {
            HomebaseTheme {
                state = rememberRichTextState()
                MessageTextFieldForAttachment(state = state, onSendMessage = {})
            }
        }

        runOnIdle { state.addTextAfterSelection(":par") }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasText(":party:")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun enterCommitsTheEmojiInsteadOfSendingTheMessage() = runComposeUiTest {
        lateinit var state: RichTextState
        var sends = 0
        setContent {
            HomebaseTheme {
                state = rememberRichTextState()
                MessageTextFieldForAttachment(state = state, onSendMessage = { sends++ })
            }
        }

        onNodeWithTag(ATTACHMENT_CAPTION_FIELD_TAG).requestFocus()
        runOnIdle { state.addTextAfterSelection("hi :par") }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasText(":party:")).fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithTag(ATTACHMENT_CAPTION_FIELD_TAG).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(0, sends, "Enter must commit the emoji, not send the message")
        assertEquals("hi 🎉", state.annotatedString.text)
    }

    @Test
    fun enterStillSendsWithNoListOpen() = runComposeUiTest {
        lateinit var state: RichTextState
        var sends = 0
        setContent {
            HomebaseTheme {
                state = rememberRichTextState()
                MessageTextFieldForAttachment(state = state, onSendMessage = { sends++ })
            }
        }

        onNodeWithTag(ATTACHMENT_CAPTION_FIELD_TAG).requestFocus()
        runOnIdle { state.addTextAfterSelection("just a caption") }
        waitForIdle()

        onNodeWithTag(ATTACHMENT_CAPTION_FIELD_TAG).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(1, sends)
    }

    /** The completed token is the inline replacement's job — the list must not shadow it. */
    @Test
    fun aClosingColonFallsThroughToTheInlineReplacement() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent {
            HomebaseTheme {
                state = rememberRichTextState()
                MessageTextFieldForAttachment(state = state, onSendMessage = {})
            }
        }

        runOnIdle { state.addTextAfterSelection("hi :party:") }
        waitUntil(timeoutMillis = 10_000) { state.annotatedString.text == "hi 🎉" }
    }
}
