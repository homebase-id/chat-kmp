package id.homebase.core.ui.screens.contactbook

import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.model.ContactFieldOverlay

/**
 * Applies a user [overlay] onto this (synced) entry: each non-null field replaces the synced value,
 * the display name is recomputed when the name changed, and the synced originals of the overridden
 * fields are captured in [ContactBookEntry.syncedOverlay] so the UI can reveal them. Returns the
 * entry unchanged when there is no override.
 *
 * Baselines on [ContactBookEntry.toDraft] so the name representation matches what the edit form
 * shows (given/surname falling back to the display name), keeping it consistent with
 * [buildContactOverlay].
 */
fun ContactBookEntry.withOverride(overlay: ContactFieldOverlay?): ContactBookEntry {
    if (overlay == null || overlay.isEmpty) return this
    val base = toDraft()
    val syncedOriginals = ContactFieldOverlay(
        givenName = if (overlay.givenName != null) base.givenName else null,
        surname = if (overlay.surname != null) base.surname else null,
        phone = if (overlay.phone != null) base.phone else null,
        email = if (overlay.email != null) base.email else null,
        city = if (overlay.city != null) base.city else null,
        country = if (overlay.country != null) base.country else null,
        birthday = if (overlay.birthday != null) base.birthday else null,
    )
    val newGiven = overlay.givenName ?: givenName
    val newSurname = overlay.surname ?: surname
    val newDisplay = if (overlay.givenName != null || overlay.surname != null) {
        listOfNotNull(newGiven?.ifBlank { null }, newSurname?.ifBlank { null })
            .joinToString(" ").ifBlank { displayName }
    } else {
        displayName
    }
    return copy(
        displayName = newDisplay,
        givenName = newGiven,
        surname = newSurname,
        phone = overlay.phone ?: phone,
        email = overlay.email ?: email,
        city = overlay.city ?: city,
        country = overlay.country ?: country,
        birthday = overlay.birthday ?: birthday,
        syncedOverlay = syncedOriginals.takeUnless { it.isEmpty },
        additionalPhones = overlay.additionalPhones,
        additionalEmails = overlay.additionalEmails,
    )
}

/**
 * Builds the sparse override to persist from an edit [draft] relative to [synced]'s values: a field
 * is included only when it differs from the synced value (so unchanged fields don't shadow the
 * profile). Baselines on [ContactBookEntry.toDraft] so it matches how the form was seeded.
 */
fun buildContactOverlay(draft: ContactDraft, synced: ContactBookEntry): ContactFieldOverlay {
    val base = synced.toDraft()
    fun diff(new: String, old: String): String? {
        val n = new.trim().ifBlank { null }
        return if (n != null && n != old.trim().ifBlank { null }) n else null
    }
    val newPhone = draft.phone.ifBlank { null }?.let { ContactFieldValidation.normalizePhone(it) }
    val oldPhone = base.phone.ifBlank { null }?.let { ContactFieldValidation.normalizePhone(it) }
    return ContactFieldOverlay(
        givenName = diff(draft.givenName, base.givenName),
        surname = diff(draft.surname, base.surname),
        phone = if (newPhone != null && newPhone != oldPhone) newPhone else null,
        email = diff(draft.email, base.email),
        city = diff(draft.city, base.city),
        country = diff(draft.country, base.country),
        birthday = diff(draft.birthday, base.birthday),
    )
}
