package id.homebase.api.client.auth

import id.homebase.api.common.OdinId
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

class OwnerSessionInitialsTest {

    private fun session(
        displayName: String? = null,
        firstName: String? = null,
        surName: String? = null,
    ) = OwnerSession(
        odinId = OdinId("test.homebase.id"),
        displayName = displayName,
        firstName = firstName,
        surName = surName,
        profileImageFileId = null,
        profileImageFileKey = null,
        profileImagePreviewThumbnail = null,
        profileImageLastModified = null,
        status = null,
    )

    @Test
    fun emoji_led_first_and_sur_name() {
        val result = session(firstName = "${PARTY}Ada", surName = "Lovelace").initials()
        assertEquals("${PARTY}L", result)
        assertFalse(result.hasLoneSurrogate())
    }

    @Test
    fun emoji_led_display_name() {
        val result = session(displayName = "$PARTY Ada").initials()
        assertEquals("${PARTY}A", result)
        assertFalse(result.hasLoneSurrogate())
    }

    @Test
    fun emoji_led_single_word_display_name() {
        val result = session(displayName = "${PARTY}Party").initials()
        assertEquals(PARTY, result)
        assertFalse(result.hasLoneSurrogate())
    }

    @Test
    fun plain_ascii_name_parts() {
        assertEquals("AL", session(firstName = "Ada", surName = "Lovelace").initials())
    }

    @Test
    fun plain_ascii_display_name() {
        assertEquals("AL", session(displayName = "Ada Lovelace").initials())
    }

    @Test
    fun single_word_display_name() {
        assertEquals("A", session(displayName = "Ada").initials())
    }

    @Test
    fun no_name_falls_back_to_question_mark() {
        assertEquals("?", session().initials())
    }

    @Test
    fun blank_display_name_falls_back_to_question_mark() {
        assertEquals("?", session(displayName = "   ").initials())
    }
}
