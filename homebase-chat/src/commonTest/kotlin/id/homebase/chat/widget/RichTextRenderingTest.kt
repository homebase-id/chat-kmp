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
    /**
     * An inline-only body (emphasis + a link, no block elements) must render
     * through the single-Text inline path. The link label text is visible and the
     * URL syntax is gone — the link rides as a native Compose LinkAnnotation in
     * the annotated string, which a plain Text handles without a custom
     * pointerInput. This is the path the bubble's last-line timestamp tuck wraps.
     */
    @Test
    fun chatMarkdownRendersInlineLink() = runComposeUiTest {
        setContent {
            Box(Modifier.testTag("md")) {
                ChatMarkdown(content = "see **this** [link](https://example.test) now")
            }
        }
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("see this link now").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("see this link now").assertExists()
    }

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

    /**
     * Regression guard for the Desktop runtime crash on PR #665:
     * `NoClassDefFoundError com/mikepenz/markdown/annotator/AnnotatorSettingsKt`.
     *
     * The mikepenz CORE module hosts `annotatorSettings` /
     * `buildMarkdownAnnotatedString`; [ChatMarkdown] imports both. The block path
     * here drives `annotatorSettings` (inline spans inside the heading/quote/list/
     * code blocks all flow through the annotator) PLUS the full block renderer. If
     * the core artifact is ever dropped from a consumer's classpath again, this
     * body throws on first render and the test fails.
     *
     * NOTE (honest scope): this test resolves homebase-chat's deps NORMALLY, so the
     * core is on its classpath transitively via m3 even without the explicit
     * dependency — it therefore passes both before and after the build fix and does
     * NOT reproduce the Desktop-distributable stripping (which is a property of
     * desktopApp's per-platform-JAR packaging, not of homebase-chat's own
     * classpath). It guards the renderer's *use* of the annotator API; the
     * packaging fix is validated by the Desktop app build itself.
     */
    @Test
    fun chatMarkdownRendersFullAnnotatorBlockSurface() = runComposeUiTest {
        val body = buildString {
            append("# Heading\n\n")
            append("**bold** _italic_ `inline code` [link](https://x)\n\n")
            append("- item one\n- item two\n\n")
            append("> a quote\n\n")
            append("```\ncode block\n```")
        }
        setContent {
            Box(Modifier.testTag("md")) {
                ChatMarkdown(content = body)
            }
        }
        // Reaching idle means the block renderer + annotatorSettings +
        // buildMarkdownAnnotatedString all loaded and laid out without throwing.
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Heading").fetchSemanticsNodes().isNotEmpty()
        }
        waitForIdle()
        onNodeWithTag("md").assertExists()
        onNodeWithText("Heading").assertExists()
    }

    /**
     * Inline-only body (no block elements) → the single-Text inline path, which
     * also calls `buildMarkdownAnnotatedString` + `annotatorSettings` directly (the
     * other site that touches the mikepenz core annotator class). This is the path
     * that threw the Desktop `NoClassDefFoundError` first, because it runs the
     * annotator outside the block `Markdown()` composable. Must compose + lay out
     * without throwing.
     *
     * Asserted structurally (tag still present after idle) rather than on an exact
     * concatenated string: the goal is "the annotator path renders without an
     * exception", and the visible-text shape of inline-code + a bare link is an
     * implementation detail of the renderer that shouldn't make this guard brittle.
     */
    @Test
    fun chatMarkdownRendersInlineAnnotatorSurface() = runComposeUiTest {
        setContent {
            Box(Modifier.testTag("md")) {
                ChatMarkdown(content = "**hi** `x` [l](https://e.test)")
            }
        }
        // If the annotator core class were missing, composition would throw here and
        // the test would error out before reaching idle.
        waitForIdle()
        onNodeWithTag("md").assertExists()
    }
}
