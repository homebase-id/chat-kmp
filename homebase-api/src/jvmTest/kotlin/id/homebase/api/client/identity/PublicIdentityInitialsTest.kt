package id.homebase.api.client.identity

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

class PublicIdentityInitialsTest {

    private fun identity(
        displayName: String? = null,
        firstName: String? = null,
        surName: String? = null,
        domain: String = "test.homebase.id",
    ) = PublicIdentity(
        odinId = OdinId(domain),
        displayName = displayName,
        firstName = firstName,
        surName = surName,
        status = null,
    )

    @Test
    fun emoji_led_first_and_sur_name() {
        val result = identity(firstName = "${PARTY}Ada", surName = "Lovelace").initials()
        assertEquals("${PARTY}L", result)
        assertFalse(result.hasLoneSurrogate())
    }

    @Test
    fun emoji_led_display_name() {
        val result = identity(displayName = "$PARTY Ada").initials()
        assertEquals("${PARTY}A", result)
        assertFalse(result.hasLoneSurrogate())
    }

    @Test
    fun emoji_led_single_word_display_name() {
        val result = identity(displayName = "${PARTY}Party").initials()
        assertEquals(PARTY, result)
        assertFalse(result.hasLoneSurrogate())
    }

    @Test
    fun plain_ascii_name_parts() {
        assertEquals("AL", identity(firstName = "Ada", surName = "Lovelace").initials())
    }

    @Test
    fun plain_ascii_display_name() {
        assertEquals("AL", identity(displayName = "Ada Lovelace").initials())
    }

    @Test
    fun single_word_display_name() {
        assertEquals("A", identity(displayName = "Ada").initials())
    }

    @Test
    fun no_name_falls_back_to_domain_initial() {
        assertEquals("S", identity(domain = "samwise.homebase.id").initials())
    }
}
