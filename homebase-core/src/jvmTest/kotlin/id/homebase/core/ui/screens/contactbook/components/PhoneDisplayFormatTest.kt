package id.homebase.core.ui.screens.contactbook.components

import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneDisplayFormatTest {

    @Test
    fun `formats NANP numbers as area-prefix-line`() {
        assertEquals("+1 (415) 555-0123", formatPhoneForDisplay("+14155550123"))
    }

    @Test
    fun `groups non-NANP national digits in readable chunks`() {
        // UK national 7911123456 (10 digits): 791 112 345 + orphan 6 merged -> 3456.
        assertEquals("+44 791 112 3456", formatPhoneForDisplay("+447911123456"))
        // Germany national 30123456 (8 digits): 301 234 56.
        assertEquals("+49 301 234 56", formatPhoneForDisplay("+4930123456"))
    }

    @Test
    fun `formats Denmark numbers in pairs`() {
        // Denmark's flat 8-digit plan is conventionally grouped in pairs.
        assertEquals("+45 12 34 56 78", formatPhoneForDisplay("+4512345678"))
    }

    @Test
    fun `returns the input unchanged when it is not parseable E164`() {
        // Legacy/non-E.164 data must still be shown, not mangled.
        assertEquals("555-1234", formatPhoneForDisplay("555-1234"))
        assertEquals("", formatPhoneForDisplay(""))
        // Plus-prefixed but unknown dial code: left as-is.
        assertEquals("+9990000", formatPhoneForDisplay("+9990000"))
    }
}
