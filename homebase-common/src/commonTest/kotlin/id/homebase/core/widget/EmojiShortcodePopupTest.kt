package id.homebase.core.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EmojiShortcodePopupTest {

    private fun ComposeContent(
        onSend: () -> Unit = {},
        state: (RichTextState) -> Unit,
    ): @androidx.compose.runtime.Composable () -> Unit = {
        MaterialTheme {
            val richState = rememberRichTextState()
            val controller = rememberEmojiShortcodeController()
            state(richState)
            Box {
                RichTextEditor(
                    state = richState,
                    // Mirrors MessageInputBar: the popup gets first refusal, Enter-to-send behind it.
                    modifier = Modifier
                        .emojiShortcodeAnchor(controller)
                        .onPreviewKeyEvent { event ->
                            if (controller.handleKeyEvent(event)) {
                                true
                            } else if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                                onSend()
                                true
                            } else {
                                false
                            }
                        }
                        .testTag("editor"),
                )
                EmojiShortcodePopup(state = richState, controller = controller)
            }
        }
    }

    @Test
    fun typingAShortcodeQueryShowsRankedSuggestions() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(ComposeContent { state = it })

        runOnIdle { state.addTextAfterSelection(":smil") }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasText("smiling face", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun clickingASuggestionReplacesTheQueryWithLiteralEmojiText() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(ComposeContent { state = it })

        runOnIdle { state.addTextAfterSelection("hi :smil") }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasText("smiling face", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        onAllNodes(hasText("smiling face", substring = true))[0].performClick()
        waitForIdle()

        val text = state.annotatedString.text
        assertTrue(text.startsWith("hi "), "expected the leading text to survive, got: $text")
        assertTrue(':' !in text, "expected the ':smil' query to be gone, got: $text")
        assertTrue(
            text.codePointCountCompat() == "hi ".length + 1,
            "expected exactly one emoji code point appended, got: $text",
        )
    }

    @Test
    fun aColonInsideAWordNeverOpensThePopup() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(ComposeContent { state = it })

        // The classic false positives: a URL scheme and a clock time.
        runOnIdle { state.addTextAfterSelection("https://ok and 10:30am") }
        waitForIdle()

        assertEquals(
            0,
            onAllNodes(hasText("smiling face", substring = true)).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun aOneCharacterQueryIsBelowTheThresholdAndShowsNothing() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(ComposeContent { state = it })

        runOnIdle { state.addTextAfterSelection(":s") }
        waitForIdle()

        assertEquals(0, onAllNodes(hasText("face", substring = true)).fetchSemanticsNodes().size)
    }
    @Test
    fun enterCommitsTheHighlightedEmojiInsteadOfSendingTheMessage() = runComposeUiTest {
        lateinit var state: RichTextState
        var sends = 0
        setContent(ComposeContent(onSend = { sends++ }) { state = it })

        onNodeWithTag("editor").requestFocus()
        runOnIdle { state.addTextAfterSelection("hi :smil") }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasText("smiling face", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithTag("editor").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(0, sends, "Enter must commit the emoji, not send the message")
        val text = state.annotatedString.text
        assertTrue(':' !in text, "expected the query to be replaced, got: $text")
        assertEquals("hi ".length + 1, text.codePointCountCompat())
    }

    @Test
    fun enterSendsNormallyWhenNoPopupIsShowing() = runComposeUiTest {
        lateinit var state: RichTextState
        var sends = 0
        setContent(ComposeContent(onSend = { sends++ }) { state = it })

        onNodeWithTag("editor").requestFocus()
        runOnIdle { state.addTextAfterSelection("just a message") }
        waitForIdle()

        onNodeWithTag("editor").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(1, sends)
    }

    @Test
    fun escapeDismissesThePopupAndLeavesTheTypedTextAlone() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(ComposeContent { state = it })

        onNodeWithTag("editor").requestFocus()
        runOnIdle { state.addTextAfterSelection("hi :smil") }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasText("smiling face", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithTag("editor").performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasText("smiling face", substring = true)).fetchSemanticsNodes().isEmpty()
        }

        assertEquals("hi :smil", state.annotatedString.text)
    }
}

private fun String.codePointCountCompat(): Int {
    var count = 0
    var i = 0
    while (i < length) {
        val high = this[i]
        i += if (high.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}
