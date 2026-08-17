@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.client.contacts.ContactName
import id.homebase.api.client.contacts.ContactPhone
import id.homebase.chat.contactcard.ContactCardDescriptor
import id.homebase.chat.contactcard.VCardParser
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.model.ContactFieldOverlay
import kotlinx.coroutines.test.runTest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
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
        assertEquals("Homebase", draft.organization)
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

    // --- Receiver side: a card that arrived in a chat message ---

    private fun entry(
        displayName: String,
        phone: String? = null,
        email: String? = null,
        additionalPhones: List<String> = emptyList(),
        additionalEmails: List<String> = emptyList(),
        organization: String? = null,
    ) = ContactBookEntry(
        uniqueId = Uuid.random(),
        fileId = Uuid.random(),
        versionTag = null,
        displayName = displayName,
        phone = phone,
        email = email,
        additionalPhones = additionalPhones,
        additionalEmails = additionalEmails,
        organization = organization,
    )

    @Test
    fun `seeds the contact editor straight from a received descriptor`() {
        val descriptor = ContactCardDescriptor(
            displayName = "Ada Vance",
            givenName = "Ada",
            surname = "Vance",
            phones = listOf("+1 (415) 555-0123", "+14155550999"),
            emails = listOf(" ada@example.com ", "ada@work.example"),
        )

        val draft = ContactCardImport.toDraft(descriptor)

        assertEquals("Ada", draft.givenName)
        assertEquals("Vance", draft.surname)
        assertEquals("+14155550123", draft.phone, "A card from another client is re-normalized here.")
        assertEquals("ada@example.com", draft.email)
        assertTrue(draft.isSavable)
    }

    @Test
    fun `a received descriptor seeds the editor with the organization it displayed`() {
        val draft = ContactCardImport.toDraft(
            ContactCardDescriptor(
                displayName = "Ada Vance",
                organization = "Contoso Fährverkehr 🚢 GmbH",
                phones = listOf("+14155550123"),
            ),
        )

        assertEquals(
            "Contoso Fährverkehr 🚢 GmbH",
            draft.organization,
            "The card renders it as the subtitle; saving must not drop it on the floor.",
        )
    }

    @Test
    fun `a received descriptor with no structured name uses the display name`() {
        val draft = ContactCardImport.toDraft(
            ContactCardDescriptor(displayName = "Zoë 🚀 Nakamura", phones = listOf("+4915112345678")),
        )

        assertEquals("Zoë 🚀 Nakamura", draft.givenName)
        assertEquals("", draft.surname)
    }

    @Test
    fun `an already-known phone is recognised however the book formatted it`() {
        val descriptor = ContactCardDescriptor(
            displayName = "Ada V.",
            phones = listOf("+1 (415) 555-0123"),
        )
        val book = listOf(
            entry("Someone Else", phone = "+4915112345678"),
            entry("Ada Vance", phone = "+14155550123"),
        )

        assertEquals("Ada Vance", ContactCardImport.findExisting(descriptor, book)?.displayName)
    }

    @Test
    fun `an extra phone or email on an existing contact still counts as known`() {
        val book = listOf(
            entry("Ada Vance", phone = "+4915112345678", additionalPhones = listOf("+14155550123")),
        )
        val byExtraPhone = ContactCardDescriptor(displayName = "Ada", phones = listOf("+14155550123"))
        val byExtraEmail = ContactCardDescriptor(
            displayName = "Bo",
            emails = listOf("BO@Example.com"),
        )

        assertNotNull(ContactCardImport.findExisting(byExtraPhone, book))
        assertNotNull(
            ContactCardImport.findExisting(
                byExtraEmail,
                listOf(entry("Bo", additionalEmails = listOf("bo@example.com"))),
            ),
            "Email matching is case-insensitive.",
        )
    }

    // --- The check as the save flow actually runs it ---

    private fun syncedContact(uniqueId: Uuid, displayName: String, phone: String) = Contact(
        uniqueId = uniqueId,
        versionTag = Uuid.random(),
        content = ContactContent(
            name = ContactName(displayName = displayName),
            phone = ContactPhone(phone),
        ),
    )

    @Test
    fun `the check sees an extra phone that lives only in the override`() = runTest {
        // A ContactBookEntry never carries additionalPhones on its own — the contact schema is
        // single-valued, so the extras exist only in the app-local override blob. A check built
        // straight off repo.contacts is therefore blind to every value but the canonical one.
        val id = Uuid.random()
        val contacts = listOf(syncedContact(id, "Ada Vance", phone = "+4915112345678"))
        val card = ContactCardDescriptor(displayName = "Ada", phones = listOf("+1 (415) 555-0123"))

        assertNull(
            ContactCardImport.resolveExisting(card, { contacts }, { emptyMap() }),
            "Nothing to match without the override: the synced contact holds a different number.",
        )
        assertEquals(
            "Ada Vance",
            ContactCardImport.resolveExisting(card, { contacts }) {
                mapOf(id to ContactFieldOverlay(additionalPhones = listOf("+14155550123")))
            }?.displayName,
            "Dropping .withOverride() here is the bug that made this branch unreachable.",
        )
    }

    @Test
    fun `the check compares an overridden primary phone, not its stale synced value`() = runTest {
        val id = Uuid.random()
        val contacts = listOf(syncedContact(id, "Ada Vance", phone = "+4915112345678"))
        val card = ContactCardDescriptor(displayName = "Ada", phones = listOf("+14155550123"))

        assertNotNull(
            ContactCardImport.resolveExisting(card, { contacts }) {
                mapOf(id to ContactFieldOverlay(phone = "+14155550123"))
            },
        )
    }

    @Test
    fun `the overrides are loaded for the contacts that were read, not some other list`() = runTest {
        val id = Uuid.random()
        val contacts = listOf(syncedContact(id, "Ada Vance", phone = "+4915112345678"))
        var seen: List<Contact>? = null

        ContactCardImport.resolveExisting(
            ContactCardDescriptor(displayName = "Ada"),
            { contacts },
        ) { loaded ->
            seen = loaded
            emptyMap()
        }

        assertEquals(contacts, seen, "Hydration has to be driven by the list about to be matched.")
    }

    @Test
    fun `a name-only card never blocks a save`() {
        val descriptor = ContactCardDescriptor(displayName = "Ada Vance")
        val book = listOf(entry("Ada Vance", phone = "+14155550123"))

        assertNull(
            ContactCardImport.findExisting(descriptor, book),
            "Names collide; only a phone or an email is evidence of the same person.",
        )
    }

    @Test
    fun `an unrelated book yields no match`() {
        val descriptor = ContactCardDescriptor(
            displayName = "Ada Vance",
            phones = listOf("+14155550123"),
            emails = listOf("ada@example.com"),
        )

        assertNull(ContactCardImport.findExisting(descriptor, listOf(entry("Bo", phone = "+4915112345678"))))
        assertNull(ContactCardImport.findExisting(descriptor, emptyList()))
    }

    // --- Every value on the card reaches the save, not just the first of each kind ---

    private val manyValued = ContactCardDescriptor(
        displayName = "Ada Vance",
        givenName = "Ada",
        surname = "Vance",
        phones = listOf("+1 (415) 555-0123", "+14155550999", "+4915112345678"),
        emails = listOf("ada@example.com", "ada@work.example", "ada@old.example"),
    )

    @Test
    fun `the editor is seeded with the extra values, not only the first of each kind`() {
        assertEquals("+14155550123", ContactCardImport.toDraft(manyValued).phone)
        assertEquals(
            listOf("+14155550999", "+4915112345678"),
            ContactCardImport.extraPhones(manyValued),
        )
        assertEquals("ada@example.com", ContactCardImport.toDraft(manyValued).email)
        assertEquals(
            listOf("ada@work.example", "ada@old.example"),
            ContactCardImport.extraEmails(manyValued),
        )
    }

    @Test
    fun `a single-valued card seeds no extra rows`() {
        val one = ContactCardDescriptor(displayName = "Solo", phones = listOf("+14155550123"))

        assertEquals(emptyList(), ContactCardImport.extraPhones(one))
        assertEquals(emptyList(), ContactCardImport.extraEmails(one))
    }

    @Test
    fun `every value on the card survives the round trip into the saved contact`() {
        // Primary phone/email go to contact content; the schema has no room for the rest, so they
        // ride in the app-private override — together these must cover the whole card.
        val draft = ContactCardImport.toDraft(manyValued)
        val overlay = additionsOverlay(
            ContactCardImport.extraPhones(manyValued),
            ContactCardImport.extraEmails(manyValued),
        )

        assertEquals(
            listOf("+14155550123", "+14155550999", "+4915112345678"),
            listOf(draft.phone) + overlay.additionalPhones,
        )
        assertEquals(
            listOf("ada@example.com", "ada@work.example", "ada@old.example"),
            listOf(draft.email) + overlay.additionalEmails,
        )
    }

    @Test
    fun `the additions overlay drops what the editor would not have let through`() {
        val overlay = additionsOverlay(
            listOf("", "  ", "+1 (415) 555-0999", "+14155550999", "not a number"),
            listOf("", " ada@work.example ", "ada@work.example", "tina@@example"),
        )

        assertEquals(listOf("+14155550999"), overlay.additionalPhones, "Blank, duplicate and unparseable go.")
        assertEquals(listOf("ada@work.example"), overlay.additionalEmails, "Blank, duplicate and invalid go.")
    }

    @Test
    fun `a card with nothing extra writes no override at all`() {
        assertTrue(additionsOverlay(listOf("", " "), listOf("")).isEmpty)
    }

    // --- Organization: displayed on the card, but with no slot in the contact schema ---

    @Test
    fun `the organization survives the round trip from card to saved contact and back`() {
        val received = ContactCardDescriptor(
            displayName = "Ada Vance",
            organization = "Contoso Fährverkehr 🚢 GmbH",
            phones = listOf("+14155550123"),
        )

        val draft = ContactCardImport.toDraft(received)
        val overlay = additionsOverlay(
            ContactCardImport.extraPhones(received),
            ContactCardImport.extraEmails(received),
            draft.organization,
        )

        assertEquals(
            "Contoso Fährverkehr 🚢 GmbH",
            overlay.organization,
            "ContactContent has no organization, so the override is the only store that keeps it.",
        )

        // Through withOverride, not a hand-built entry: that hop is the one that reads the
        // override back, so seeding organization directly would pass over an inert feature.
        val saved = entry("Ada Vance", phone = draft.phone).withOverride(overlay)

        assertEquals(
            received.organization,
            ContactCardImport.toDescriptor(saved)?.organization,
            "Re-sharing the saved contact has to put the organization back on the card.",
        )
    }

    @Test
    fun `an organization alone is enough to need an override`() {
        val overlay = additionsOverlay(emptyList(), emptyList(), "Contoso")

        assertFalse(overlay.isEmpty, "Otherwise the save skips the override write and loses it.")
        assertEquals("Contoso", overlay.organization)
    }

    @Test
    fun `the additions overlay trims a blank organization away and caps a long one`() {
        assertNull(additionsOverlay(emptyList(), emptyList(), "   ").organization)
        assertEquals("Contoso", additionsOverlay(emptyList(), emptyList(), " Contoso ").organization)

        val capped = assertNotNull(
            additionsOverlay(emptyList(), emptyList(), "🚢".repeat(90)).organization,
        )
        assertEquals("🚢".repeat(ContactCardDescriptor.MAX_NAME_CODEPOINTS), capped)
        assertTrue(
            ContactCardDescriptor(displayName = "Ada", organization = capped).isValid(),
            "An over-cap organization would make every re-share of this contact unshareable.",
        )
    }

    @Test
    fun `a contact with no organization leaves the field off the card`() {
        assertEquals("", ContactCardImport.toDescriptor(entry("Ada", phone = "+14155550123"))?.organization)
    }
}
