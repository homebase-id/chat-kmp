package id.homebase.chat.services.chat

import id.homebase.api.util.markdownToPlainPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the unified markdown -> plain-text preview strip
 * ([markdownToPlainPreview]). This replaced two divergent regex strip paths
 * ([id.homebase.chat.services.chat.ChatMessageSizer.preview] and
 * `String.stripMarkdownForPreview`), and these tests pin the behaviour the old
 * regex got wrong.
 */
class MarkdownPlainTest {

    @Test
    fun preservesMidWordHyphensAndUnderscores() {
        // The old ChatMessageSizer regex `[*_`#>\-]` deleted these mid-word,
        // mangling plain identifiers / phrases. The AST walk must leave them be.
        val input = "well-being and snake_case_name and co-op deal"
        val out = markdownToPlainPreview(input, maxCodePoints = 200)
        assertEquals("well-being and snake_case_name and co-op deal", out)
    }

    @Test
    fun stripsHeadingEmphasisCodeAndQuoteMarkers() {
        val input = "# Heading\n\nSome **bold** and _italic_ and `code` text.\n\n> a quote"
        val out = markdownToPlainPreview(input, maxCodePoints = 200)
        assertTrue(!out.contains("#"), "heading marker leaked: $out")
        assertTrue(!out.contains("**"), "bold marker leaked: $out")
        assertTrue(!out.contains("`"), "code marker leaked: $out")
        assertTrue(!out.contains(">"), "quote marker leaked: $out")
        assertTrue(out.contains("Heading"))
        assertTrue(out.contains("bold"))
        assertTrue(out.contains("italic"))
        assertTrue(out.contains("code"))
        assertTrue(out.contains("a quote"))
    }

    @Test
    fun keepsLinkLabelAndDropsUrl() {
        val input = "See [the docs](https://example.test/path?q=1) for details"
        val out = markdownToPlainPreview(input, maxCodePoints = 200)
        assertTrue(out.contains("the docs"), "link label dropped: $out")
        assertTrue(!out.contains("https://"), "link URL leaked: $out")
        assertTrue(!out.contains("example.test"), "link URL leaked: $out")
    }

    @Test
    fun keepsImageAltAndDropsUrl() {
        val input = "Look: ![a red dot](https://img.test/dot.png) nice"
        val out = markdownToPlainPreview(input, maxCodePoints = 200)
        assertTrue(out.contains("a red dot"), "image alt dropped: $out")
        assertTrue(!out.contains("https://"), "image URL leaked: $out")
        assertTrue(!out.contains(".png"), "image URL leaked: $out")
    }

    @Test
    fun collapsesNewlinesToSpaces() {
        val input = "line one\n\nline two\nline three"
        val out = markdownToPlainPreview(input, maxCodePoints = 200)
        assertTrue(!out.contains("\n"), "newline leaked: $out")
        assertEquals("line one line two line three", out)
    }

    @Test
    fun truncatesOnCodePointBoundaryForEmoji() {
        // Each 😀 is a surrogate pair (2 chars, 1 code point). Asking for 3 code
        // points must return exactly 3 whole emoji (6 chars), never split a pair.
        val input = "😀😀😀😀😀"
        val out = markdownToPlainPreview(input, maxCodePoints = 3)
        assertEquals(3, out.codePointCountCompat())
        assertEquals(6, out.length) // 3 surrogate pairs, no lone surrogate
    }

    @Test
    fun emptyInputReturnsEmpty() {
        assertEquals("", markdownToPlainPreview("", maxCodePoints = 200))
    }
}

/** Counts Unicode code points, treating surrogate pairs as one. */
private fun String.codePointCountCompat(): Int {
    var count = 0
    var i = 0
    while (i < length) {
        i += if (i + 1 < length && this[i].isHighSurrogate() && this[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}
