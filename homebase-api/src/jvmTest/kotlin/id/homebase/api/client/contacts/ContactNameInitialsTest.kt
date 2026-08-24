package id.homebase.api.client.contacts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val PARTY = "🎉" // U+1F389 party popper

private fun String.hasLoneSurrogate(): Boolean {
    var i = 0
    while (i < length) {
        val c = this[i]
        when {
            c.isHighSurrogate() -> {
                if (i + 1 >= length || !this[i + 1].isLowSurrogate()) return true
                i += 2
            }

            c.isLowSurrogate() -> return true
            else -> i += 1
        }
    }
    return false
}

class ContactNameInitialsTest {

    @Test
    fun emoji_led_given_and_surname() {
        val result = ContactName(givenName = "${PARTY}Ada", surname = "Lovelace").initials()
        assertEquals("${PARTY}L", result)
        assertFalse(result.hasLoneSurrogate())
    }

    @Test
    fun emoji_led_display_name() {
        val result = ContactName(displayName = "$PARTY Ada").initials()
        assertEquals("${PARTY}A", result)
        assertFalse(result.hasLoneSurrogate())
    }

    @Test
    fun last_word_starting_with_emoji() {
        val result = ContactName(displayName = "Ada ${PARTY}Lovelace").initials()
        assertEquals("A$PARTY", result)
        assertFalse(result.hasLoneSurrogate())
    }

    @Test
    fun emoji_led_single_word_display_name() {
        val result = ContactName(displayName = "${PARTY}Party").initials()
        assertEquals(PARTY, result)
        assertFalse(result.hasLoneSurrogate())
    }

    @Test
    fun plain_ascii_name_parts() {
        assertEquals("AL", ContactName(givenName = "Ada", surname = "Lovelace").initials())
    }

    @Test
    fun plain_ascii_display_name() {
        assertEquals("AL", ContactName(displayName = "Ada Lovelace").initials())
    }

    @Test
    fun single_word_display_name() {
        assertEquals("A", ContactName(displayName = "Ada").initials())
    }

    @Test
    fun null_name_falls_back_to_question_mark() {
        assertEquals("?", (null as ContactName?).initials())
    }

    @Test
    fun blank_display_name_falls_back_to_question_mark() {
        assertEquals("?", ContactName(displayName = "   ").initials())
    }
}
