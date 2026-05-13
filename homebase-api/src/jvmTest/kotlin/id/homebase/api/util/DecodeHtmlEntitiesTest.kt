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

    @Test
    fun decodes_real_x_twitter_og_description() {
        val raw = """Bjorn Lomborg on X: &quot;There is no energy transition

We simply use more and more of everything

— fossil fuels, nuclear, renewables, solar and wind

&quot;Rather than replacing fossil fuels, renewables are adding to the overall energy mix&quot;
Energy Institute Statistical Review 2025

https://t.co/mS2nxOCFjW https://t.co/RfWM24m8GX&quot; / X"""

        val decoded = raw.decodeHtmlEntities()
        assertFalse(decoded.contains("&quot;"), "All &quot; entities should be decoded")
        assertTrue(decoded.contains("\"There is no energy transition"))
        assertTrue(decoded.contains("\"Rather than replacing fossil fuels"))
        assertTrue(decoded.endsWith("\" / X"))
    }
}
