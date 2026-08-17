package id.homebase.core.ui.screens.contactbook

import id.homebase.chat.contactcard.VCardParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The vCard → contact-card / contact-editor hop: everything the share flow does between
 * parsing the shared file and either sending it or seeding [ContactEditSheet].
 */
class ContactCardImportTest {

    private fun parse(card: String) = assertNotNull(VCardParser.parseFirst(card.trimIndent()))

    @Test
    fun `normalizes a formatted international phone to E164`() {
        val contact = parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Ada Vance
            TEL;TYPE=CELL:+1 (415) 555-0123
            EMAIL: ada@example.com
            END:VCARD
            """
        )

        val descriptor = assertNotNull(ContactCardImport.toDescriptor(contact))

        assertEquals(listOf("+14155550123"), descriptor.phones)
        assertEquals(listOf("ada@example.com"), descriptor.emails)
        assertTrue(ContactFieldValidation.isValidPhone(descriptor.phones.first()))
    }

    @Test
    fun `a legacy non-E164 phone is kept and flagged, not dropped`() {
        val contact = parse(
            """
            BEGIN:VCARD
            VERSION:2.1
            FN:Legacy Larry
            TEL;HOME:0207 946 0018
            END:VCARD
            """
        )

        val descriptor = assertNotNull(ContactCardImport.toDescriptor(contact))
        val draft = ContactCardImport.toDraft(contact)

        assertEquals(listOf("02079460018"), descriptor.phones, "The number must survive the import.")
        assertEquals("02079460018", draft.phone, "It seeds PhoneNumberField so the user can see it.")
        assertFalse(draft.phoneValid, "…and it must be flagged, which gates Save until corrected.")
        assertFalse(draft.isSavable)
    }

    @Test
    fun `seeds the contact editor from the parsed card`() {
        val contact = parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            N:Vance;Ada;;;
            FN:Ada Vance
            ORG:Homebase
            TEL:+14155550123
            EMAIL:ada@example.com
            END:VCARD
            """
        )

        val draft = ContactCardImport.toDraft(contact)

        assertEquals("Ada", draft.givenName)
        assertEquals("Vance", draft.surname)
        assertEquals("+14155550123", draft.phone)
        assertEquals("ada@example.com", draft.email)
        assertTrue(draft.isSavable, "A well-formed card must be savable straight from the prefill.")
    }

    @Test
    fun `a card with no structured N puts the whole display name in the first-name slot`() {
        val contact = parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Zoë 🚀 Nakamura
            TEL:+4915112345678
            END:VCARD
            """
        )

        val draft = ContactCardImport.toDraft(contact)

        assertEquals("Zoë 🚀 Nakamura", draft.givenName)
        assertEquals("", draft.surname)
    }

    @Test
    fun `a card with only an organization produces no descriptor`() {
        // Parses (there IS a property) but carries no name, phone or email — nothing that makes
        // a usable contact card, so the share falls back to sending the raw file.
        val contact = parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            ORG:Acme
            X-SOMETHING:value
            END:VCARD
            """
        )

        assertNull(ContactCardImport.toDescriptor(contact))
    }

    @Test
    fun `a card with no usable properties does not parse at all`() {
        assertNull(
            VCardParser.parseFirst("BEGIN:VCARD\nVERSION:3.0\nFN:\nTEL:\nEMAIL:\nEND:VCARD"),
        )
    }

    @Test
    fun `an invalid email is carried and flagged rather than silently dropped`() {
        val contact = parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Typo Tina
            EMAIL:tina@@example
            END:VCARD
            """
        )

        val descriptor = assertNotNull(ContactCardImport.toDescriptor(contact))
        val draft = ContactCardImport.toDraft(contact)

        assertEquals(listOf("tina@@example"), descriptor.emails)
        assertFalse(draft.emailValid)
        assertFalse(draft.isSavable)
    }
}
