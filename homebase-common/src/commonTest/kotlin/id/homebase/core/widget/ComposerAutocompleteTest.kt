package id.homebase.core.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val Candidates = listOf("smile", "smirk", "smiley")

@OptIn(ExperimentalTestApi::class)
class ComposerAutocompleteTest {

    /**
     * Mirrors the three composer editors in `MessageInputBar`: a Box wrapping ONLY the editor is
     * the popup's anchor, and the editor's preview-key handler gives the controller first refusal
     * before Enter-to-send.
     */
    private fun harness(
        enabled: Boolean = true,
        onSend: () -> Unit = {},
        capture: (RichTextState) -> Unit,
    ): @Composable () -> Unit = {
        MaterialTheme {
            val state = rememberRichTextState()
            val controller = rememberComposerAutocompleteController()
            capture(state)
            Box {
                RichTextEditor(
                    state = state,
                    modifier = Modifier
                        .onPreviewKeyEvent { event ->
                            if (controller.handleKeyEvent(event)) {
                                true
                            } else if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                                if (event.isShiftPressed) state.addTextAfterSelection("\n") else onSend()
                                true
                            } else {
                                false
                            }
                        }
                        .testTag("editor"),
                )
                ComposerAutocomplete(
                    state = state,
                    controller = controller,
                    triggerId = "test",
                    triggerChar = ':',
                    suggestionsFor = { query ->
                        if (query.length < 2) emptyList()
                        else Candidates.filter { it.startsWith(query) }
                    },
                    replacementFor = { "<$it>" },
                    enabled = enabled,
                ) { item, selected ->
                    Text(text = if (selected) "*$item" else item)
                }
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.awaitList() =
        waitUntil(timeoutMillis = 10_000) {
            onAllNodes(hasText("smile", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun typingTheTriggerAndAQueryOpensTheList() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        runOnIdle { state.addTextAfterSelection(":sm") }
        awaitList()

        onNodeWithTag(ComposerAutocompleteTag).assertExists()
    }

    @Test
    fun aOneCharacterQueryStaysBelowTheThreshold() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        runOnIdle { state.addTextAfterSelection(":s") }
        waitForIdle()

        assertEquals(0, onAllNodes(hasText("smile", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun aTriggerInsideAWordNeverOpensTheList() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        // The classic false positives: a URL scheme and a clock time.
        runOnIdle { state.addTextAfterSelection("https://sm and 10:sm") }
        waitForIdle()

        assertEquals(0, onAllNodes(hasText("smile", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun theListNeverOpensWhenDisabled() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness(enabled = false) { state = it })

        runOnIdle { state.addTextAfterSelection(":sm") }
        waitForIdle()

        assertEquals(0, onAllNodes(hasText("smile", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun clickingAnItemSplicesItsReplacementOverTheTriggerToken() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        runOnIdle { state.addTextAfterSelection("hi :sm") }
        awaitList()

        onNodeWithText("*smile").performClick()
        waitForIdle()

        assertEquals("hi <smile>", state.annotatedString.text)
    }

    @Test
    fun enterCommitsTheSelectedItemInsteadOfSendingTheMessage() = runComposeUiTest {
        lateinit var state: RichTextState
        var sends = 0
        setContent(harness(onSend = { sends++ }) { state = it })

        onNodeWithTag("editor").requestFocus()
        runOnIdle { state.addTextAfterSelection("hi :sm") }
        awaitList()

        onNodeWithTag("editor").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(0, sends, "Enter must commit the suggestion, not send the message")
        assertEquals("hi <smile>", state.annotatedString.text)
    }

    @Test
    fun enterStillSendsWhenNoListIsShowing() = runComposeUiTest {
        lateinit var state: RichTextState
        var sends = 0
        setContent(harness(onSend = { sends++ }) { state = it })

        onNodeWithTag("editor").requestFocus()
        runOnIdle { state.addTextAfterSelection("just a message") }
        waitForIdle()

        onNodeWithTag("editor").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(1, sends)
    }

    @Test
    fun shiftEnterStillInsertsANewlineWithTheListOpen() = runComposeUiTest {
        lateinit var state: RichTextState
        var sends = 0
        setContent(harness(onSend = { sends++ }) { state = it })

        onNodeWithTag("editor").requestFocus()
        runOnIdle { state.addTextAfterSelection("hi :sm") }
        awaitList()

        onNodeWithTag("editor").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        waitForIdle()

        assertEquals(0, sends)
        assertTrue(
            state.annotatedString.text.startsWith("hi :sm"),
            "Shift+Enter must not commit, got: ${state.annotatedString.text}",
        )
    }

    @Test
    fun arrowKeysMoveTheSelectionAndEnterCommitsIt() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        onNodeWithTag("editor").requestFocus()
        runOnIdle { state.addTextAfterSelection(":sm") }
        awaitList()

        onNodeWithTag("editor").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onNodeWithText("*smirk").assertExists()

        onNodeWithTag("editor").performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        onNodeWithText("*smile").assertExists()

        onNodeWithTag("editor").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals("<smile>", state.annotatedString.text)
    }

    /**
     * richeditor paints the live trigger token in its link colour. That is presentation only — it
     * must not reach the markdown the composer sends.
     */
    @Test
    fun anOpenTriggerNeverLeaksStylingIntoTheMarkdown() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        runOnIdle { state.addTextAfterSelection("hi :sm") }
        awaitList()

        assertEquals("hi :sm", state.toMarkdown())
    }

    @Test
    fun escapeDismissesTheListAndLeavesTheTypedTextAlone() = runComposeUiTest {
        lateinit var state: RichTextState
        setContent(harness { state = it })

        onNodeWithTag("editor").requestFocus()
        runOnIdle { state.addTextAfterSelection("hi :sm") }
        awaitList()

        onNodeWithTag("editor").performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodes(hasText("smile", substring = true)).fetchSemanticsNodes().isEmpty()
        }

        assertEquals("hi :sm", state.annotatedString.text)
    }
}
