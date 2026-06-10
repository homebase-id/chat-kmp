package id.homebase.api.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives the inline-vs-block decision in `ChatMarkdown`. Inline-only bodies must
 * stay on the single-Text path (so they remain compatible with the message
 * bubble's timestamp-tucking custom Layout); anything with a real block element
 * must route to the block renderer.
 */
class MarkdownHasBlockElementsTest {

    @Test
    fun inlineEmphasisLinkAndCode_isNotBlock() {
        assertFalse(markdownHasBlockElements("**hi** [x](u) `c`"))
    }

    @Test
    fun plainText_isNotBlock() {
        assertFalse(markdownHasBlockElements("just a normal message, no markdown"))
    }

    @Test
    fun emptyAndBlank_isNotBlock() {
        assertFalse(markdownHasBlockElements(""))
        assertFalse(markdownHasBlockElements("   "))
    }

    @Test
    fun strikethroughAndItalic_isNotBlock() {
        // GFM strikethrough + italic are inline spans, not blocks.
        assertFalse(markdownHasBlockElements("~~gone~~ and _slanted_"))
    }

    @Test
    fun softWrappedSingleParagraph_isNotBlock() {
        // A single paragraph that happens to wrap across source lines (single
        // newlines, no blank line) is still one paragraph.
        assertFalse(markdownHasBlockElements("line one\nline two\nline three"))
    }

    @Test
    fun atxHeading_isBlock() {
        assertTrue(markdownHasBlockElements("# H"))
    }

    @Test
    fun setextHeading_isBlock() {
        assertTrue(markdownHasBlockElements("Title\n====="))
    }

    @Test
    fun unorderedList_isBlock() {
        assertTrue(markdownHasBlockElements("- a"))
    }

    @Test
    fun orderedList_isBlock() {
        assertTrue(markdownHasBlockElements("1. first\n2. second"))
    }

    @Test
    fun blockQuote_isBlock() {
        assertTrue(markdownHasBlockElements(">q"))
    }

    @Test
    fun fencedCodeBlock_isBlock() {
        assertTrue(markdownHasBlockElements("```\ncode line\n```"))
    }

    @Test
    fun indentedCodeBlock_isBlock() {
        assertTrue(markdownHasBlockElements("    indented code"))
    }

    @Test
    fun thematicBreak_isBlock() {
        assertTrue(markdownHasBlockElements("---"))
    }

    @Test
    fun gfmTable_isBlock() {
        val table = buildString {
            append("| a | b |\n")
            append("| - | - |\n")
            append("| 1 | 2 |\n")
        }
        assertTrue(markdownHasBlockElements(table))
    }

    @Test
    fun twoParagraphs_isBlock() {
        // A blank line between paragraphs creates a paragraph gap the single
        // inline Text path cannot reproduce — treat it as block.
        assertTrue(markdownHasBlockElements("first para\n\nsecond para"))
    }
}
