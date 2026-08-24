package id.homebase.core.ui.screens.contactbook

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactFieldValidationTest {

    @Test
    fun `blank birthday is valid`() {
        assertTrue(ContactFieldValidation.isValidBirthday(""))
        assertTrue(ContactFieldValidation.isValidBirthday("   "))
    }

    @Test
    fun `well-formed iso dates are valid`() {
        assertTrue(ContactFieldValidation.isValidBirthday("1969-02-22"))
        assertTrue(ContactFieldValidation.isValidBirthday("2000-12-31"))
        assertTrue(ContactFieldValidation.isValidBirthday(" 1969-02-22 "))
    }

    @Test
    fun `malformed shapes are rejected`() {
        // The reported bug: a third digit in the day slot saved happily.
        assertFalse(ContactFieldValidation.isValidBirthday("1969-02-222"))
        assertFalse(ContactFieldValidation.isValidBirthday("1969-2-2"))
        assertFalse(ContactFieldValidation.isValidBirthday("02/22/1969"))
        assertFalse(ContactFieldValidation.isValidBirthday("1969"))
        assertFalse(ContactFieldValidation.isValidBirthday("Feb 22 1969"))
        assertFalse(ContactFieldValidation.isValidBirthday("1969-02-22T00:00:00Z"))
    }

    @Test
    fun `impossible calendar dates are rejected`() {
        assertFalse(ContactFieldValidation.isValidBirthday("1969-13-01"))
        assertFalse(ContactFieldValidation.isValidBirthday("1969-00-10"))
        assertFalse(ContactFieldValidation.isValidBirthday("1969-02-00"))
        assertFalse(ContactFieldValidation.isValidBirthday("1969-04-31"))
        assertFalse(ContactFieldValidation.isValidBirthday("0000-01-01"))
    }

    @Test
    fun `leap day follows the gregorian rule`() {
        assertTrue(ContactFieldValidation.isValidBirthday("2024-02-29"))
        assertTrue(ContactFieldValidation.isValidBirthday("2000-02-29"))
        assertFalse(ContactFieldValidation.isValidBirthday("2023-02-29"))
        assertFalse(ContactFieldValidation.isValidBirthday("1900-02-29"))
    }
}
