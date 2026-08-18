package id.homebase.core.ui.screens.contactbook

import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry

// Primary slots and additional rows are decided together: separately, the same number lands in both.
data class MergeSeed(
    val draft: ContactDraft,
    val additionalPhones: List<String>,
    val additionalEmails: List<String>,
)

// The identity is deliberately never filled from [seed]: merging is the one place a remote card
// could bind an odinId onto a contact that had none, which every later render would then fetch.
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
            // A card with no structured name puts the whole formatted name in givenName.
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
