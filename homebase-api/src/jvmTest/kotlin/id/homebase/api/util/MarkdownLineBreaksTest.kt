package id.homebase.api.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownLineBreaksTest {

    @Test
    fun singleNewlineBecomesHardBreak() {
        // A soft break inside a paragraph gains the two-space CommonMark hard break.
        assertEquals("alpha  \nbeta", "alpha\nbeta".withChatHardLineBreaks())
    }

    @Test
    fun blankLineParagraphBreakUnchanged() {
        assertEquals("alpha\n\nbeta", "alpha\n\nbeta".withChatHardLineBreaks())
    }

    @Test
    fun newlineInsideFencedCodeUntouched() {
        val src = "```\nline a\nline b\n```"
        // No "  \n" injected anywhere inside the fence.
        assertEquals(src, src.withChatHardLineBreaks())
    }

    @Test
    fun newlineInsideIndentedCodeUntouched() {
        val src = "    line a\n    line b"
        assertEquals(src, src.withChatHardLineBreaks())
    }

    @Test
    fun tableStructurePreserved() {
        val src = "| a | b |\n| - | - |\n| 1 | 2 |"
        val out = src.withChatHardLineBreaks()
        // The pipe rows are NOT paragraph soft breaks, so the table parses unchanged.
        assertTrue(markdownHasBlockElements(out), "table must still be a block element")
        assertEquals(src, out)
    }

    @Test
    fun listStructurePreserved() {
        val src = "- one\n- two"
        val out = src.withChatHardLineBreaks()
        assertTrue(markdownHasBlockElements(out), "list must still be a block element")
    }

    @Test
    fun crlfNormalisedAndConverted() {
        assertEquals("alpha  \nbeta", "alpha\r\nbeta".withChatHardLineBreaks())
    }

    @Test
    fun blankAndEmptyAreSafe() {
        assertEquals("", "".withChatHardLineBreaks())
        assertEquals("   ", "   ".withChatHardLineBreaks())
    }

    @Test
    fun trailingSingleNewlineNotPadded() {
        // A lone trailing newline is not a paragraph-internal soft break.
        assertEquals("alpha\n", "alpha\n".withChatHardLineBreaks())
    }
}
