package id.homebase.api.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecodeHtmlEntitiesTest {

    @Test
    fun decodes_named_entities() {
        assertEquals("A & B", "A &amp; B".decodeHtmlEntities())
        assertEquals("1 < 2 > 0", "1 &lt; 2 &gt; 0".decodeHtmlEntities())
        assertEquals("He said \"hi\"", "He said &quot;hi&quot;".decodeHtmlEntities())
    }

    @Test
    fun decodes_numeric_decimal_entities() {
        assertEquals("A", "&#65;".decodeHtmlEntities())
        assertEquals("©", "&#169;".decodeHtmlEntities())
    }

    @Test
    fun decodes_numeric_hex_entities() {
        assertEquals("A", "&#x41;".decodeHtmlEntities())
        assertEquals("€", "&#x20AC;".decodeHtmlEntities())
    }

    @Test
    fun preserves_unknown_named_entities() {
        assertEquals("&foo;", "&foo;".decodeHtmlEntities())
    }

    @Test
    fun returns_input_unchanged_when_no_ampersand() {
        val input = "Hello world"
        assertEquals(input, input.decodeHtmlEntities())
    }

    @Test
    fun decodes_mixed_entities_in_real_og_title() {
        assertEquals(
            "Tom's Diner – A \"Classic\" Bar & Grill",
            "Tom&apos;s Diner &ndash; A &quot;Classic&quot; Bar &amp; Grill".decodeHtmlEntities()
        )
    }

    @Test
    fun handles_emoji_surrogate_pair_from_numeric_entity() {
        assertEquals("😀", "&#128512;".decodeHtmlEntities())
    }
}

class SanitizePreviewTextTest {

    @Test
    fun collapses_newlines_to_single_space() {
        assertEquals(
            "first second third",
            "first\n\nsecond\n\n\nthird".sanitizePreviewText()
        )
    }

    @Test
    fun collapses_mixed_whitespace() {
        assertEquals("a b c", "a \t\n b   c".sanitizePreviewText())
    }

    @Test
    fun trims_leading_and_trailing_whitespace() {
        assertEquals("hello", "  hello  ".sanitizePreviewText())
    }

    @Test
    fun inserts_space_before_quote_sandwiched_between_words() {
        assertEquals(
            "wind \"Rather than",
            "wind\"Rather than".sanitizePreviewText()
        )
    }

    @Test
    fun decodes_entities_and_spaces_quotes_together() {
        assertEquals(
            "wind \"Rather",
            "wind&quot;Rather".sanitizePreviewText()
        )
    }

    @Test
    fun full_pipeline_real_x_twitter_og_description() {
        val raw = "Bjorn Lomborg on X: &quot;There is no energy transition\n\n" +
            "We simply use more and more of everything\n\n" +
            "— fossil fuels, nuclear, renewables, solar and wind\n\n" +
            "&quot;Rather than replacing fossil fuels, renewables are adding to the overall energy mix&quot;\n" +
            "Energy Institute Statistical Review 2025\n\n" +
            "https://t.co/mS2nxOCFjW https://t.co/RfWM24m8GX&quot; / X"

        val result = raw.sanitizePreviewText()

        assertFalse(result.contains("&quot;"), "HTML entities should be decoded")
        assertFalse(result.contains('\n'), "Newlines should be collapsed")
        assertTrue(
            result.contains("transition We"),
            "Words across paragraph breaks should have a space"
        )
        assertTrue(
            result.contains("wind \"Rather"),
            "Quote should be spaced from preceding word"
        )
    }

    @Test
    fun full_pipeline_no_newlines_server_stripped() {
        val raw = "solar and wind" +
            "&quot;Rather than replacing fossil fuels&quot; / X"

        val result = raw.sanitizePreviewText()

        assertTrue(
            result.contains("wind \"Rather"),
            "Quote jammed against word should get a space even without newlines"
        )
        assertFalse(result.contains("wind\""), "No quote should be jammed against a word")
    }

    @Test
    fun preserves_already_clean_text() {
        val clean = "Datalogisk Institut – Københavns Universitet"
        assertEquals(clean, clean.sanitizePreviewText())
    }

    @Test
    fun preserves_properly_spaced_quotes() {
        val text = "He said \"hello\" to everyone"
        assertEquals(text, text.sanitizePreviewText())
    }
}
