package id.homebase.chat.widget

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchHighlightTest {

    private val color = Color.Yellow

    @Test
    fun emptyQueryReturnsNull() {
        assertNull(buildSearchHighlightedText("hello world", "", color))
    }

    @Test
    fun noMatchReturnsNull() {
        assertNull(buildSearchHighlightedText("hello world", "zzz", color))
    }

    @Test
    fun caseInsensitiveMatch() {
        val result = buildSearchHighlightedText("Monday Monday", "mon", color)!!
        assertEquals("Monday Monday", result.text)
        val ranges = result.spanStyles
        assertEquals(2, ranges.size)
        assertEquals(0, ranges[0].start); assertEquals(3, ranges[0].end)
        assertEquals(7, ranges[1].start); assertEquals(10, ranges[1].end)
    }

    @Test
    fun allRangesFitWithinPlainText_forLongMarkdownMessage() {
        // The original crash happened at length=4253 with end=4321 — span end
        // exceeded drawn string length. Build a realistic long markdown body
        // and assert the invariant that every produced SpanStyle has
        // end <= text.length and start in [0, end].
        val body = buildString {
            append("# Heading\n\n")
            repeat(40) { append("- list item with `code` and **bold** and _italic_ Mon\n") }
            repeat(60) { append("This is a long paragraph with Monday somewhere inside.\n") }
        }
        val result = buildSearchHighlightedText(body, "Mon", color)!!
        val plainLen = result.text.length
        assertTrue(plainLen > 4000, "sanity: body is expected to be large, was $plainLen")
        for (range in result.spanStyles) {
            assertTrue(
                range.end <= plainLen,
                "end=${range.end} exceeds plainLen=$plainLen"
            )
            assertTrue(range.start in 0..range.end)
        }
    }

    @Test
    fun matchAtVeryEndOfString() {
        // Regression for the exact shape of the crash: a match whose end index
        // equals plain.length must land exactly at plain.length, never past it.
        val body = "x".repeat(4250) + "Mon"
        val result = buildSearchHighlightedText(body, "Mon", color)!!
        val last = result.spanStyles.last()
        assertEquals(body.length, last.end)
    }
}
