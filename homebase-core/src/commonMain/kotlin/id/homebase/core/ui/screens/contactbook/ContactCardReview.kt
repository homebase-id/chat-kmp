package id.homebase.core.ui.screens.contactbook

import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.contactcard.ContactCardDescriptor
import id.homebase.chat.contactcard.scrubbed
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

enum class ContactCardFieldKind { Phone, Email, Organization, Identity }

data class ContactCardField(
    val kind: ContactCardFieldKind,
    val value: String,
    val selected: Boolean = true,
)

/** A projection of [entry] for one send: nothing here is ever written back to the contact book. */
data class ContactCardReview(
    val entry: ContactBookEntry,
    val source: ContactCardDescriptor,
    val displayName: String,
    val fields: PersistentList<ContactCardField>,
    val includePhoto: Boolean,
) {
    val hasPhoto: Boolean get() = entry.imagePayload != null

    val selectedCount: Int get() = fields.count { it.selected }

    // A card that had values and now carries none renders as bare initials on the receiver.
    val canSend: Boolean
        get() = (fields.isEmpty() || selectedCount > 0) && toDescriptor().isValid()

    fun toDescriptor(): ContactCardDescriptor {
        val renamed = displayName.trim() != source.displayName.trim()
        return source.copy(
            displayName = displayName.scrubbed()
                .truncateToCodePoints(ContactCardDescriptor.MAX_NAME_CODEPOINTS),
            // The receiver saves from the structured name when it has one, which would undo a rename.
            givenName = if (renamed) "" else source.givenName,
            surname = if (renamed) "" else source.surname,
            organization = kept(ContactCardFieldKind.Organization) ?: source.organization,
            odinId = kept(ContactCardFieldKind.Identity) ?: source.odinId,
            phones = selectedValues(ContactCardFieldKind.Phone),
            emails = selectedValues(ContactCardFieldKind.Email),
        )
    }

    fun toggle(index: Int): ContactCardReview = fields.getOrNull(index)
        ?.let { copy(fields = fields.set(index, it.copy(selected = !it.selected))) }
        ?: this

    // Null when the review never offered the field, so a value it doesn't model — an odinId that
    // doesn't parse as an identity — is carried through rather than silently dropped.
    private fun kept(kind: ContactCardFieldKind): String? =
        fields.firstOrNull { it.kind == kind }?.let { if (it.selected) it.value else "" }

    private fun selectedValues(kind: ContactCardFieldKind): List<String> =
        fields.filter { it.kind == kind && it.selected }
            .map { it.value }
            .take(ContactCardDescriptor.MAX_VALUES_PER_KIND)

    companion object {
        fun from(entry: ContactBookEntry, descriptor: ContactCardDescriptor): ContactCardReview {
            val fields = buildList {
                descriptor.phones.forEach { add(ContactCardField(ContactCardFieldKind.Phone, it)) }
                descriptor.emails.forEach { add(ContactCardField(ContactCardFieldKind.Email, it)) }
                descriptor.organization.takeIf { it.isNotBlank() }?.let {
                    add(ContactCardField(ContactCardFieldKind.Organization, it))
                }
                descriptor.identity()?.let {
                    add(ContactCardField(ContactCardFieldKind.Identity, descriptor.odinId))
                }
            }
            return ContactCardReview(
                entry = entry,
                source = descriptor,
                displayName = descriptor.displayName,
                fields = fields.toPersistentList(),
                includePhoto = entry.imagePayload != null,
            )
        }
    }
}
