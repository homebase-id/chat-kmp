package id.homebase.core.ui.screens.contactbook

import id.homebase.api.serialization.OdinSystemSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Asserts the JSON, not the object. `ContactPhone`/`ContactEmail` both take `label` first, so a
 * positional `ContactPhone(it)` files the number under the label and posts no number at all — the
 * object still looks populated, the request still returns 200, and the contact saves without a
 * phone or an email. Only the wire form shows it.
 */
class BuildContactContentTest {

    private fun json(draft: ContactDraft) = OdinSystemSerializer.serialize(
        buildContactContent(draft, editing = null, normalizedPhone = draft.phone.ifBlank { null }),
    )

    @Test
    fun `a phone is posted as a number, not as a label`() {
        val body = json(ContactDraft(givenName = "Ada", phone = "+14155550123"))

        assertTrue(body.contains(""""number":"+14155550123""""), body)
        assertTrue(body.contains(""""phone":{"number""""), "phone must not lead with label: $body")
    }

    @Test
    fun `an email is posted as an email, not as a label`() {
        val body = json(ContactDraft(givenName = "Ada", email = "ada@example.com"))

        assertTrue(body.contains(""""email":{"email":"ada@example.com"}"""), body)
    }

    @Test
    fun `a birthday is posted as a date`() {
        val body = json(ContactDraft(givenName = "Ada", birthday = "1815-12-10"))

        assertTrue(body.contains(""""birthday":{"date":"1815-12-10"}"""), body)
    }

    @Test
    fun `a draft with every simple field round-trips onto the wire`() {
        val body = json(
            ContactDraft(
                givenName = "Ada",
                surname = "Lovelace",
                phone = "+14155550123",
                email = "ada@example.com",
                odinId = "ada.demo.rocks",
            ),
        )

        val parsed = OdinSystemSerializer.deserialize<
            id.homebase.api.client.contacts.ContactContent,
            >(body)
        assertEquals("+14155550123", parsed.phone?.number)
        assertEquals("ada@example.com", parsed.email?.email)
        assertEquals("ada.demo.rocks", parsed.odinId)
    }
}
