package id.homebase.chat.contactcard

import kotlin.test.Test
import kotlin.test.assertEquals

class ContactCardDeviceContactTest {

    @Test
    fun `a blank display name falls back to the structured name`() {
        val contact = ContactCardDescriptor(displayName = "", givenName = "Ada", surname = "Vance").toDeviceContact()

        assertEquals("Ada Vance", contact.displayName)
        assertEquals("Ada", contact.givenName)
        assertEquals("Vance", contact.surname)
    }

    @Test
    fun `bidi and control characters are stripped`() {
        val contact = ContactCardDescriptor(
            displayName = "Ada‮ Vance\u0007",
            givenName = "A​da",
            surname = "Va⁦nce",
            organization = "Home‪base",
        ).toDeviceContact()

        assertEquals("Ada Vance", contact.displayName)
        assertEquals("Ada", contact.givenName)
        assertEquals("Vance", contact.surname)
        assertEquals("Homebase", contact.organization)
    }

    @Test
    fun `website is set only for a valid identity`() {
        assertEquals(
            "https://ada.example.com",
            ContactCardDescriptor(displayName = "Ada", odinId = "ada.example.com").toDeviceContact().website,
        )
        assertEquals(
            "",
            ContactCardDescriptor(displayName = "Ada", odinId = "not an identity/../x").toDeviceContact().website,
        )
        assertEquals("", ContactCardDescriptor(displayName = "Ada").toDeviceContact().website)
    }

    @Test
    fun `all phones and emails are kept in order`() {
        val phones = listOf("+14155550123", "+4512345678", "0123")
        val emails = listOf("b@example.com", "a@example.com")
        val contact = ContactCardDescriptor(displayName = "Ada", phones = phones, emails = emails).toDeviceContact()

        assertEquals(phones, contact.phones)
        assertEquals(emails, contact.emails)
    }
}
