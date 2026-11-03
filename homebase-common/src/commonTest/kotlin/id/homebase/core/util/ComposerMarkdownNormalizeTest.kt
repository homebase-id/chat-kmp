package id.homebase.core.util

import com.mohamedrejeb.richeditor.model.RichTextState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals

/**
 * richeditor's [com.mohamedrejeb.richeditor.model.RichTextState.toMarkdown] serialises an empty
 * paragraph as a standalone `<br>` line. A composer holding a stray blank line round-trips to
 * `"\n<br>"`, and a link with an empty line above it to `"\n<br>\nhttps://…"` (both byte-verified
 * against rc14). That `<br>` is WYSIWYG noise: it shows as a literal `<br>` on the web client and a
 * blank bubble on mobile (#1104). [stripComposerLineBreakArtifacts] removes it without disturbing
 * real text or intentional paragraph breaks.
 */
class ComposerMarkdownNormalizeTest {

    @Test
    fun pureBlankLineArtifact_becomesEmpty() {
        // setText("\n") / setMarkdown("<br>") both serialise to this.
        assertEquals("", "\n<br>".stripComposerLineBreakArtifacts())
    }

    @Test
    fun leadingBrBeforeLink_keepsOnlyTheLink() {
        // setMarkdown("<br>\nurl") serialises to this — the exact shape in the #1104 report.
        assertEquals(
            "https://homebase.id",
            "\n<br>\nhttps://homebase.id".stripComposerLineBreakArtifacts(),
        )
    }

    @Test
    fun trailingBlankLine_isTrimmed() {
        assertEquals("https://homebase.id", "https://homebase.id\n".stripComposerLineBreakArtifacts())
    }

    @Test
    fun plainText_isUntouched() {
        assertEquals("hello world", "hello world".stripComposerLineBreakArtifacts())
    }

    @Test
    fun intentionalParagraphBreak_isPreserved() {
        // An intentional blank line between paragraphs serialises as "\n\n" (NOT "<br>"),
        // so it must survive normalization.
        assertEquals("para one\n\npara two", "para one\n\npara two".stripComposerLineBreakArtifacts())
    }

    @Test
    fun captionThenLink_isPreserved() {
        assertEquals(
            "check this\nhttps://homebase.id",
            "check this\nhttps://homebase.id".stripComposerLineBreakArtifacts(),
        )
    }

    @Test
    fun blankString_becomesEmpty() {
        assertEquals("", "   ".stripComposerLineBreakArtifacts())
    }

    @Test
    fun richTextStateWithTrailingBlankLine_toMessageMarkdownHasNoBr() {
        val state = RichTextState().apply { setText("hello\n") }
        assertEquals("hello", state.toMessageMarkdown())
        assertFalse(state.toMessageMarkdown().contains("<br>"))
    }

    @Test
    fun brLineInsideFencedCode_isKept() {
        val md = "intro\n\n```kotlin\nval a = 1\n<br>\nval b = 2\n```\n\n<br>\noutro"
        assertEquals(
            "intro\n\n```kotlin\nval a = 1\n<br>\nval b = 2\n```\n\noutro",
            md.stripComposerLineBreakArtifacts(),
        )
    }

    @Test
    fun brLineInsideTildeFence_isKept() {
        val md = "~~~\n<br>\n~~~"
        assertEquals(md, md.stripComposerLineBreakArtifacts())
    }
}
