package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * Rendering regressions for the chat markdown RENDERER, now the mikepenz
 * CommonMark renderer wrapped by [ChatMarkdown] (the read-only display path).
 * The editor stays on richeditor; these cover the display side only.
 */
@OptIn(ExperimentalTestApi::class)
class RichTextRenderingTest {

    @Test
    fun chatMarkdownRendersBoldAndItalic() = runComposeUiTest {
        setContent {
            Box(Modifier.testTag("md")) {
                ChatMarkdown(content = "*Bold* _Italic_")
            }
        }

        // mikepenz parses on a background dispatcher (non-immediate); wait until
        // the paragraph node materialises, then assert the visible text matches.
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Bold Italic").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Bold Italic").assertExists()
    }

    /**
     * The old richeditor `setMarkdown` parser crashed on a space at the start of
     * a line following an empty line (`gg\n\n  gg`). The mikepenz CommonMark
     * parser handles it without the `fixProblematicMarkdownText` workaround —
     * this asserts the swap fixed the crash: the body must render without
     * throwing.
     */
    @Test
    fun chatMarkdownDoesNotCrashOnSpaceAfterBlankLine() = runComposeUiTest {
        val testContent = "gg\n\n  gg"
        setContent {
            Box(Modifier.testTag("md")) {
                ChatMarkdown(content = testContent)
            }
        }
        // Reaching here means composition + parse completed without an exception.
        waitForIdle()
        onNodeWithTag("md").assertExists()
    }

    /**
     * A body mixing a heading, a blockquote, a fenced code block and a list must
     * render through the M3-styled block path without crashing — proving the new
     * authoring element set displays.
     */
    @Test
    fun chatMarkdownRendersBlockElements() = runComposeUiTest {
        val body = buildString {
            append("# Title\n\n")
            append("> a quote\n\n")
            append("```\ncode line\n```\n\n")
            append("- one\n- two\n")
        }
        setContent {
            Box(Modifier.testTag("md")) {
                ChatMarkdown(content = body)
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Title").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("Title").assertExists()
    }
}
