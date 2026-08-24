package id.homebase.core.ui.screens.contactbook.components

import id.homebase.core.ui.screens.contactbook.ContactFieldValidation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneComposeE164Test {

    private fun country(iso: String) = countries.first { it.iso == iso }

    @Test
    fun `drops the UK trunk zero when the user picks a country for a national number`() {
        val composed = composeE164(country("GB"), "0207 946 0018")
        assertEquals("+442079460018", composed)
        assertTrue(ContactFieldValidation.isValidPhone(composed))
    }

    @Test
    fun `keeps the leading zero where it belongs to the national number`() {
        // Italian landlines carry the 0; stripping it produces a number that does not dial.
        assertEquals("+390669821234", composeE164(country("IT"), "06 6982 1234"))
        assertEquals("+3780549882000", composeE164(country("SM"), "0549 882000"))
        assertEquals("+2250712345678", composeE164(country("CI"), "07 12 34 56 78"))
    }

    @Test
    fun `italian mobile numbers are unaffected`() {
        assertEquals("+393331234567", composeE164(country("IT"), "333 123 4567"))
    }

    @Test
    fun `numbers already in E164 round-trip unchanged`() {
        for (stored in listOf("+442079460018", "+14155550123", "+390669821234", "+4930123456")) {
            val (c, national) = splitE164(stored)
            assertEquals(stored, composeE164(requireNotNull(c), national))
        }
    }

    @Test
    fun `blank input stays blank`() {
        assertEquals("", composeE164(country("GB"), ""))
        assertEquals("", composeE164(country("GB"), "   "))
        assertEquals("", composeE164(country("GB"), "0"))
    }

    @Test
    fun `only a single leading zero is dropped`() {
        assertEquals("+44079460018", composeE164(country("GB"), "0079460018"))
    }

    @Test
    fun `a national number without a trunk zero is untouched`() {
        assertEquals("+442079460018", composeE164(country("GB"), "2079460018"))
        assertEquals("+14155550123", composeE164(country("US"), "415 555 0123"))
        assertEquals("+4930123456", composeE164(country("DE"), "30123456"))
    }
}
