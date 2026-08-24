@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * ContactBookEntry.displayName is resolved, not stored: for a contact with no name of its own it
 * falls back to the odinId, phone or email. Seeding the editor's name field from it showed the user
 * a phone number in "First name" and, on save, wrote that fallback into the contact.
 */
class ContactDraftSeedTest {

    private fun entry(
        displayName: String,
        givenName: String? = null,
        surname: String? = null,
        phone: String? = null,
        email: String? = null,
        odinId: String? = null,
        organization: String? = null,
    ) = ContactBookEntry(
        uniqueId = Uuid.random(),
        fileId = Uuid.random(),
        versionTag = null,
        displayName = displayName,
        givenName = givenName,
        surname = surname,
        phone = phone,
        email = email,
        odinId = odinId,
        organization = organization,
    )

    @Test
    fun `a phone-only contact seeds no name at all`() {
        val draft = entry("+14155550123", phone = "+14155550123").toDraft()

        assertEquals("", draft.givenName, "The phone is a rendering fallback, not the contact's name.")
        assertEquals("+14155550123", draft.phone)
    }

    @Test
    fun `an identity-only contact seeds no name either`() {
        val draft = entry("samwise.gamgee.demo.rocks", odinId = "samwise.gamgee.demo.rocks").toDraft()

        assertEquals("", draft.givenName)
        assertEquals("samwise.gamgee.demo.rocks", draft.odinId)
    }

    @Test
    fun `a real stored name still seeds the name field`() {
        assertEquals("Ada Vance", entry("Ada Vance", phone = "+14155550123").toDraft().givenName)
        assertEquals("Ada", entry("Ada Vance", givenName = "Ada", surname = "Vance").toDraft().givenName)
    }

    // ContactContent has no organization leaf, so a contact carrying only one resolves to no
    // display name and is filtered out of every list — written to the drive and invisible.
    @Test
    fun `an organization alone is not enough to save`() {
        assertFalse(ContactDraft(organization = "Acme").isSavable)
        assertTrue(ContactDraft(organization = "Acme", givenName = "Ada").isSavable)
        assertTrue(ContactDraft(organization = "Acme", phone = "+14155550123").isSavable)
    }
}
