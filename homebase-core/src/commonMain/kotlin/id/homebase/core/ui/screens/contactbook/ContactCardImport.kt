package id.homebase.core.ui.screens.contactbook

import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.contactcard.ContactCardDescriptor
import id.homebase.chat.contactcard.VCardContact

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
            phones = contact.normalizedPhones(),
            emails = contact.normalizedEmails(),
        )
        return descriptor.takeIf { it.isValid() }
    }

    fun toDraft(contact: VCardContact): ContactDraft {
        val given = contact.givenName.cap()
        val surname = contact.surname.cap()
        // No structured N: the whole formatted name goes in the first-name slot, matching what
        // ContactBookEntry.toDraft does for a contact that only has a display name.
        val fallbackGiven = if (given.isBlank() && surname.isBlank()) contact.displayName.cap() else given
        return ContactDraft(
            givenName = fallbackGiven,
            surname = surname,
            phone = contact.normalizedPhones().firstOrNull().orEmpty(),
            email = contact.normalizedEmails().firstOrNull().orEmpty(),
        )
    }

    private fun VCardContact.normalizedPhones(): List<String> = phones
        .map { ContactFieldValidation.normalizePhone(it) }
        .filter { it.isNotBlank() }
        .distinct()
        .take(ContactCardDescriptor.MAX_VALUES_PER_KIND)
        .map { it.truncateToCodePoints(ContactCardDescriptor.MAX_VALUE_CODEPOINTS) }

    private fun VCardContact.normalizedEmails(): List<String> = emails
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(ContactCardDescriptor.MAX_VALUES_PER_KIND)
        .map { it.truncateToCodePoints(ContactCardDescriptor.MAX_VALUE_CODEPOINTS) }

    private fun String.cap(): String =
        truncateToCodePoints(ContactCardDescriptor.MAX_NAME_CODEPOINTS)
}
