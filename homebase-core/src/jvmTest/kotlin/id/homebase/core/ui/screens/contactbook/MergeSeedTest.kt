@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The primary slots and the additional rows are seeded from the same card. Deciding them in two
 * places that didn't know about each other put the same phone number in both — visible in the sheet
 * and then written to the contact twice, permanently.
 */
class MergeSeedTest {

    private fun entry(
        displayName: String,
        givenName: String? = null,
        surname: String? = null,
        phone: String? = null,
        email: String? = null,
        odinId: String? = null,
        organization: String? = null,
        additionalPhones: List<String> = emptyList(),
        additionalEmails: List<String> = emptyList(),
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
        additionalPhones = additionalPhones,
        additionalEmails = additionalEmails,
    )

    private val card = ContactDraft(
        givenName = "Ada Vance",
        phone = "+14155550123",
        email = "ada@example.com",
        odinId = "tracker.evil.tld",
        organization = "Acme",
    )

    @Test
    fun `a value the primary slot takes is not also an extra row`() {
        val target = entry("samwise.gamgee.demo.rocks", odinId = "samwise.gamgee.demo.rocks")

        val seeded = mergeSeed(target, card, listOf("+14155550123"), listOf("ada@example.com"))

        assertEquals("+14155550123", seeded.draft.phone)
        assertEquals(emptyList(), seeded.additionalPhones, "The primary already shows this number.")
        assertEquals("ada@example.com", seeded.draft.email)
        assertEquals(emptyList(), seeded.additionalEmails)
    }

    @Test
    fun `the same number in a different format is still recognised as the primary`() {
        val seeded = mergeSeed(entry("Ada", phone = null), card, listOf("+1 (415) 555-0123"), emptyList())

        assertEquals(emptyList(), seeded.additionalPhones)
    }

    @Test
    fun `a genuinely different extra survives`() {
        val seeded = mergeSeed(entry("Ada"), card, listOf("+14155550999"), listOf("ada@work.example"))

        assertEquals(listOf("+14155550999"), seeded.additionalPhones)
        assertEquals(listOf("ada@work.example"), seeded.additionalEmails)
    }

    // Rendering a contact fetches https://<odinId>/pub/image, so binding an identity the card chose
    // onto a contact that had none is the beacon the card's own avatar gate refuses.
    @Test
    fun `a merge never takes the card's identity`() {
        val target = entry("Ada", phone = "+14155550123")

        val seeded = mergeSeed(target, card, emptyList(), emptyList())

        assertEquals("", seeded.draft.odinId, "A phone match must not bind a remote identity.")
    }

    @Test
    fun `an identity the target already holds is untouched`() {
        val target = entry("Todd", odinId = "samwise.gamgee.demo.rocks")

        val seeded = mergeSeed(target, card, emptyList(), emptyList())

        assertEquals("samwise.gamgee.demo.rocks", seeded.draft.odinId)
    }

    @Test
    fun `the target wins every field it holds`() {
        val target = entry(
            "Todd Mitchell",
            givenName = "Todd",
            surname = "Mitchell",
            phone = "+14155559999",
            email = "todd@example.com",
            organization = "Contoso",
        )

        val seeded = mergeSeed(target, card, emptyList(), emptyList())

        assertEquals("Todd", seeded.draft.givenName)
        assertEquals("Mitchell", seeded.draft.surname)
        assertEquals("+14155559999", seeded.draft.phone)
        assertEquals("todd@example.com", seeded.draft.email)
        assertEquals("Contoso", seeded.draft.organization)
    }

    @Test
    fun `a gap the target leaves is filled from the card`() {
        val seeded = mergeSeed(entry("+14155559999", phone = "+14155559999"), card, emptyList(), emptyList())

        assertEquals("Ada Vance", seeded.draft.givenName, "The target has no stored name to keep.")
        assertEquals("+14155559999", seeded.draft.phone)
        assertEquals("ada@example.com", seeded.draft.email)
        assertEquals("Acme", seeded.draft.organization)
    }

    // A card with no structured name puts the whole formatted name in givenName; dropping it beside
    // a stored surname renders "Ada Lovelace Lovelace".
    @Test
    fun `a target holding only a surname does not gain a duplicated name`() {
        val seeded = mergeSeed(
            entry("Lovelace", surname = "Lovelace"),
            ContactDraft(givenName = "Ada Lovelace"),
            emptyList(),
            emptyList(),
        )

        assertEquals("", seeded.draft.givenName)
        assertEquals("Lovelace", seeded.draft.surname)
    }

    @Test
    fun `a new contact takes the card wholesale`() {
        val seeded = mergeSeed(null, card, listOf("+14155550999"), emptyList())

        assertEquals(card, seeded.draft)
        assertEquals(listOf("+14155550999"), seeded.additionalPhones)
    }

    @Test
    fun `a card's surname fills a target that has only a given name`() {
        val seeded = mergeSeed(
            entry("Ada", givenName = "Ada"),
            ContactDraft(givenName = "Ada", surname = "Lovelace"),
            emptyList(),
            emptyList(),
        )

        assertEquals("Ada", seeded.draft.givenName)
        assertEquals("Lovelace", seeded.draft.surname)
    }

    @Test
    fun `a target whose given name is the whole name does not gain a duplicate surname`() {
        val seeded = mergeSeed(
            entry("Ada Lovelace", givenName = "Ada Lovelace"),
            ContactDraft(givenName = "Ada Lovelace", surname = "Lovelace"),
            emptyList(),
            emptyList(),
        )

        assertEquals("Ada Lovelace", seeded.draft.givenName)
        assertEquals("", seeded.draft.surname)
    }

    // A substring test would read "Ada" out of "Adam" and drop a real surname.
    @Test
    fun `a surname that is a substring of the given name still fills`() {
        val seeded = mergeSeed(
            entry("Adam", givenName = "Adam"),
            ContactDraft(givenName = "Adam", surname = "Ada"),
            emptyList(),
            emptyList(),
        )

        assertEquals("Ada", seeded.draft.surname)
    }

    @Test
    fun `an extra the target already holds is not duplicated by the card`() {
        val target = entry("Ada", phone = "+14155559999", additionalPhones = listOf("+14155550123"))

        val seeded = mergeSeed(target, card, listOf("+14155550123"), emptyList())

        assertEquals(listOf("+14155550123"), seeded.additionalPhones)
        assertTrue(seeded.draft.phone == "+14155559999")
    }
}
