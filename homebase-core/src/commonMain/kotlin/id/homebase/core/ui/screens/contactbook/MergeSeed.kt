package id.homebase.core.ui.screens.contactbook

import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry

/**
 * What a seeded [ContactEditSheet] starts with. Computed in one place because the primary slots and
 * the additional rows are drawn from the same source: deciding them separately let the same phone
 * number land in both, and the sheet is the only code that knows which value the primary took.
 */
data class MergeSeed(
    val draft: ContactDraft,
    val additionalPhones: List<String>,
    val additionalEmails: List<String>,
)

/**
 * [seed] fills only what [editing] leaves blank — the contact being merged into always wins — and
 * an additional row is dropped when the primary slot has just taken that same value.
 *
 * The identity is deliberately **not** filled from [seed]. Merging is the one place a remote card
 * could bind an odinId onto a contact that had none, and every render of that contact then fetches
 * `https://<odinId>/pub/image`, which is the beacon the card's own avatar gate exists to prevent.
 * It costs nothing to refuse: when the match was made *on* the identity the target already has it,
 * so filling it only ever changes the phone/email-matched case — exactly the hostile one. The field
 * stays editable, so a user who means to link the contact still can.
 */
fun mergeSeed(
    editing: ContactBookEntry?,
    seed: ContactDraft?,
    seedAdditionalPhones: List<String>,
    seedAdditionalEmails: List<String>,
): MergeSeed {
    val base = editing?.toDraft()
    val draft = when {
        base == null -> seed ?: ContactDraft()
        seed == null -> base
        else -> base.copy(
            // A card with no structured name puts the whole formatted name in givenName, so a
            // target already holding either part must not gain a second copy of it.
            givenName = if (base.givenName.isBlank() && base.surname.isBlank()) {
                seed.givenName
            } else {
                base.givenName
            },
            surname = base.surname.ifBlank {
                seed.surname.takeIf { it.isNotBlank() && !base.givenName.hasWord(it) }.orEmpty()
            },
            phone = base.phone.ifBlank { seed.phone },
            email = base.email.ifBlank { seed.email },
            organization = base.organization.ifBlank { seed.organization },
        )
    }

    val phones = (editing?.additionalPhones.orEmpty() + seedAdditionalPhones)
        .distinct()
        .filterNot { row ->
            draft.phone.isNotBlank() &&
                ContactFieldValidation.normalizePhone(row) ==
                ContactFieldValidation.normalizePhone(draft.phone)
        }
    val emails = (editing?.additionalEmails.orEmpty() + seedAdditionalEmails)
        .distinct()
        .filterNot { row -> draft.email.isNotBlank() && row.trim().equals(draft.email.trim(), true) }

    return MergeSeed(draft = draft, additionalPhones = phones, additionalEmails = emails)
}

private fun String.hasWord(word: String): Boolean =
    split(' ').any { it.equals(word, ignoreCase = true) }
