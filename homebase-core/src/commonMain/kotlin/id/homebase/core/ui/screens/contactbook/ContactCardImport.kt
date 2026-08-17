@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.contacts.Contact
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.contactcard.ContactCardDescriptor
import id.homebase.chat.contactcard.VCardContact
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.model.ContactFieldOverlay
import id.homebase.core.ui.screens.contactbook.model.toContactBookEntry
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Turns a parsed vCard into the two shapes the share flow needs: the wire descriptor for the
 * chat contact card, and a [ContactDraft] that seeds the contact editor.
 *
 * Phones run through [ContactFieldValidation.normalizePhone] so a well-formed international
 * number lands as E.164. A value that still isn't E.164 afterwards (a national-format legacy
 * number, an extension) is kept verbatim rather than dropped — the editor seeds it into
 * `PhoneNumberField`, flags it, and blocks Save until it's corrected.
 */
object ContactCardImport {

    fun toDescriptor(contact: VCardContact): ContactCardDescriptor? {
        val descriptor = ContactCardDescriptor(
            displayName = contact.displayName.cap(),
            givenName = contact.givenName.cap(),
            surname = contact.surname.cap(),
            organization = contact.organization.cap(),
            phones = contact.phones.normalizedPhones(),
            emails = contact.emails.normalizedEmails(),
        )
        return descriptor.takeIf { it.isValid() }
    }

    fun toDescriptor(entry: ContactBookEntry): ContactCardDescriptor? {
        val descriptor = ContactCardDescriptor(
            displayName = entry.displayName.cap(),
            givenName = entry.givenName.orEmpty().cap(),
            surname = entry.surname.orEmpty().cap(),
            organization = entry.organization.orEmpty().cap(),
            odinId = entry.odinId.orEmpty().trim(),
            phones = (listOfNotNull(entry.phone) + entry.additionalPhones).normalizedPhones(),
            emails = (listOfNotNull(entry.email) + entry.additionalEmails).normalizedEmails(),
        )
        return descriptor.takeIf { it.isValid() }
    }

    fun toDraft(contact: VCardContact): ContactDraft = draft(
        givenName = contact.givenName.cap(),
        surname = contact.surname.cap(),
        displayName = contact.displayName.cap(),
        organization = contact.organization.cap(),
        phones = contact.phones,
        emails = contact.emails,
    )

    /** Only the parsed form: a garbage odinId would fail the editor's own validation on Save. */
    private fun ContactCardDescriptor.identityOrBlank(): String =
        identity()?.domainName.orEmpty()

    // Receiver side. Capped and normalized like the vCard overload: a card authored by another
    // client never went through toDescriptor, so nothing upstream has enforced the limits.
    fun toDraft(descriptor: ContactCardDescriptor): ContactDraft = draft(
        givenName = descriptor.givenName.cap(),
        surname = descriptor.surname.cap(),
        displayName = descriptor.displayName.cap(),
        organization = descriptor.organization.cap(),
        odinId = descriptor.identityOrBlank(),
        phones = descriptor.phones,
        emails = descriptor.emails,
    )

    /** Everything past the single canonical slot [toDraft] fills. */
    fun extraPhones(descriptor: ContactCardDescriptor): List<String> =
        descriptor.phones.normalizedPhones().drop(1)

    fun extraEmails(descriptor: ContactCardDescriptor): List<String> =
        descriptor.emails.normalizedEmails().drop(1)

    // [loadOverrides] is not optional: the contact schema is single-valued, so extra phones/emails —
    // and any edited primary — live only in the override blob, invisible to the synced contacts.
    suspend fun resolveExisting(
        descriptor: ContactCardDescriptor,
        loadContacts: suspend () -> List<Contact>,
        loadOverrides: suspend (List<Contact>) -> Map<Uuid, ContactFieldOverlay>,
    ): ContactBookEntry? {
        val contacts = loadContacts()
        val overrides = loadOverrides(contacts)
        return findExisting(
            descriptor,
            contacts.mapNotNull { it.toContactBookEntry()?.withOverride(overrides[it.uniqueId]) },
        )
    }

    // Value-based on purpose: names collide (two "Mum"s) and a name-only card must not block a
    // legitimate save.
    fun findExisting(
        descriptor: ContactCardDescriptor,
        entries: List<ContactBookEntry>,
    ): ContactBookEntry? {
        val phones = descriptor.phones
            .map { ContactFieldValidation.normalizePhone(it) }
            .filter { it.isNotBlank() }
            .toSet()
        val emails = descriptor.emails.mapNotNull { it.trim().lowercase().ifBlank { null } }.toSet()
        // Unlike a phone or an address, an identity is globally unique — two contacts holding it
        // are the same person, so it matches first and rescues the name-only identity card.
        val identity = descriptor.identity()?.domainName
        if (phones.isEmpty() && emails.isEmpty() && identity == null) return null

        return entries.firstOrNull { entry ->
            identity != null && entry.odinId?.trim().equals(identity, ignoreCase = true)
        } ?: entries.firstOrNull { entry ->
            entry.everyPhone().any { it in phones } || entry.everyEmail().any { it in emails }
        }
    }

    private fun ContactBookEntry.everyPhone(): List<String> =
        (listOfNotNull(phone) + additionalPhones)
            .map { ContactFieldValidation.normalizePhone(it) }
            .filter { it.isNotBlank() }

    private fun ContactBookEntry.everyEmail(): List<String> =
        (listOfNotNull(email) + additionalEmails)
            .mapNotNull { it.trim().lowercase().ifBlank { null } }

    private fun draft(
        givenName: String,
        surname: String,
        displayName: String,
        organization: String,
        phones: List<String>,
        emails: List<String>,
        odinId: String = "",
    ): ContactDraft {
        // No structured N: the whole formatted name goes in the first-name slot, matching what
        // ContactBookEntry.toDraft does for a contact that only has a display name.
        val fallbackGiven = if (givenName.isBlank() && surname.isBlank()) displayName else givenName
        return ContactDraft(
            givenName = fallbackGiven,
            surname = surname,
            organization = organization,
            odinId = odinId,
            phone = phones.normalizedPhones().firstOrNull().orEmpty(),
            email = emails.normalizedEmails().firstOrNull().orEmpty(),
        )
    }

    private fun List<String>.normalizedPhones(): List<String> = this
        .map { ContactFieldValidation.normalizePhone(it) }
        .filter { it.isNotBlank() }
        .distinct()
        .take(ContactCardDescriptor.MAX_VALUES_PER_KIND)
        .map { it.truncateToCodePoints(ContactCardDescriptor.MAX_VALUE_CODEPOINTS) }

    private fun List<String>.normalizedEmails(): List<String> = this
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(ContactCardDescriptor.MAX_VALUES_PER_KIND)
        .map { it.truncateToCodePoints(ContactCardDescriptor.MAX_VALUE_CODEPOINTS) }

    private fun String.cap(): String =
        truncateToCodePoints(ContactCardDescriptor.MAX_NAME_CODEPOINTS)
}
