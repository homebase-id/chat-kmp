package id.homebase.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val PARTY = "🎉" // U+1F389 party popper
private const val CJK_EXT_B = "𠀀" // U+20000 CJK extension B ideograph

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

class StringInitialsTest {

    @Test
    fun emoji_led_single_word() {
        val result = "${PARTY}Party".initials()
        assertEquals(PARTY, result)
        assertFalse(result.hasLoneSurrogate())
    }

    @Test
    fun emoji_only_name_keeps_the_whole_code_point() {
        val result = PARTY.initials()
        assertEquals(PARTY, result)
        assertFalse(result.hasLoneSurrogate())
    }

    @Test
    fun emoji_led_first_and_last_name() {
        val result = "$PARTY Ada".initials()
        assertEquals("${PARTY}A", result)
        assertFalse(result.hasLoneSurrogate())
    }

    @Test
    fun last_word_starting_with_emoji() {
        val result = "Ada ${PARTY}Lovelace".initials()
        assertEquals("A$PARTY", result)
        assertFalse(result.hasLoneSurrogate())
    }

    @Test
    fun both_words_starting_with_emoji() {
        val result = "$PARTY $PARTY".initials()
        assertEquals("$PARTY$PARTY", result)
        assertFalse(result.hasLoneSurrogate())
    }

    @Test
    fun non_bmp_cjk_extension_name() {
        val result = "$CJK_EXT_B Wang".initials()
        assertEquals("${CJK_EXT_B}W", result)
        assertFalse(result.hasLoneSurrogate())
    }

    @Test
    fun plain_ascii_first_and_last() {
        assertEquals("AL", "Ada Lovelace".initials())
    }

    @Test
    fun plain_ascii_is_uppercased() {
        assertEquals("AL", "ada lovelace".initials())
    }

    @Test
    fun middle_names_use_first_and_last_token() {
        assertEquals("AL", "Ada King Lovelace".initials())
    }

    @Test
    fun single_word() {
        assertEquals("A", "Ada".initials())
    }

    @Test
    fun surrounding_whitespace_is_trimmed() {
        assertEquals("AL", "  Ada Lovelace  ".initials())
    }

    @Test
    fun empty_string() {
        assertEquals("", "".initials())
    }

    @Test
    fun blank_string() {
        assertEquals("", "   ".initials())
    }

    @Test
    fun whitespace_only_tabs_and_newlines() {
        assertEquals("", " \t\n ".initials())
    }
}
