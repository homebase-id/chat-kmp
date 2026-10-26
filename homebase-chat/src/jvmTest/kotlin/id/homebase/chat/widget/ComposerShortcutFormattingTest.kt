package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor
import id.homebase.core.util.toMessageMarkdown
import id.homebase.core.widget.composerKeyHandler
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ComposerShortcutFormattingTest {

    private fun run(verify: androidx.compose.ui.test.ComposeUiTest.(RichTextState) -> Unit) = runComposeUiTest {
        lateinit var state: RichTextState
        setContent {
            MaterialTheme {
                state = rememberRichTextState()
                RichTextEditor(
                    state = state,
                    modifier = Modifier
                        .composerKeyHandler(
                            enterSendsMessage = true,
                            onSend = {},
                            onFormat = state::applyFormatShortcut,
                        )
                        .testTag("editor"),
                )
            }
        }
        onNodeWithTag("editor").requestFocus()
        runOnIdle { state.addTextAfterSelection("hi") }
        waitForIdle()
        verify(state)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.chord(modifiers: List<Key>, key: Key) {
        onNodeWithTag("editor").performKeyInput {
            fun press(rest: List<Key>) {
                if (rest.isEmpty()) pressKey(key) else withKeyDown(rest.first()) { press(rest.drop(1)) }
            }
            press(modifiers)
        }
        waitForIdle()
    }

    @Test
    fun `ctrl B on a selection bolds it`() = run { state ->
        runOnIdle { state.selection = TextRange(0, 2) }
        chord(listOf(Key.CtrlLeft), key = Key.B)
        assertEquals("**hi**", state.toMessageMarkdown())
    }

    @Test
    fun `ctrl shift 8 makes the line a bullet`() = run { state ->
        chord(listOf(Key.CtrlLeft, Key.ShiftLeft), key = Key.Eight)
        assertEquals("- hi", state.toMessageMarkdown())
    }

    @Test
    fun `ctrl Z then ctrl Y restores the formatting`() = run { state ->
        runOnIdle { state.selection = TextRange(0, 2) }
        chord(listOf(Key.CtrlLeft), key = Key.B)
        assertEquals("**hi**", state.toMessageMarkdown())
        chord(listOf(Key.CtrlLeft), key = Key.Z)
        assertEquals("hi", state.toMessageMarkdown())
        chord(listOf(Key.CtrlLeft), key = Key.Y)
        assertEquals("**hi**", state.toMessageMarkdown())
    }
}
