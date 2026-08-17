@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.contacts.ContactBirthday
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.client.contacts.ContactEmail
import id.homebase.api.client.contacts.ContactLocation
import id.homebase.api.client.contacts.ContactName
import id.homebase.api.client.contacts.ContactPhone
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.core.contactbook.ContactOverrideStore
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.model.ContactBookSource
import id.homebase.core.ui.screens.contactbook.model.ContactFieldOverlay
import id.homebase.core.util.contentType
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Outcome of [saveContactDraft]. */
sealed interface ContactSaveResult {
    /**
     * Saved. [photoFailed] = contact saved but avatar upload failed. [clearedFieldsIgnored] = the
     * edit blanked a previously-set field, which the V2 server merge can't express (empty = "leave
     * existing alone"), so that field was kept — the UI should tell the user clearing isn't
     * supported yet. The repository already applied the optimistic update, so callers push nothing.
     */
    data class Success(
        val photoFailed: Boolean,
        val clearedFieldsIgnored: Boolean = false,
    ) : ContactSaveResult

    /** Server rejected the write with 403 — the app token lacks the manage-contacts permission. */
    data object Forbidden : ContactSaveResult

    /** Any other failure (transport, 5xx, contention). */
    data object Failed : ContactSaveResult
}

/**
 * Builds [ContactContent] from a [draft] and saves it through [repo] (which owns the optimistic
 * update), then uploads the [photo] if any. Shared by the contact-list and contact-detail
 * ViewModels so edit behaves identically in both. Distinguishes a 403 (Forbidden) so the UI can
 * explain the missing-permission cause rather than a generic failure.
 */
suspend fun saveContactDraft(
    repo: ContactRepository,
    draft: ContactDraft,
    editing: ContactBookEntry?,
    photo: PlatformFile?,
): ContactSaveResult {
    if (!draft.isSavable) return ContactSaveResult.Failed

    val normalizedPhone = draft.phone.ifBlank { null }
        ?.let { ContactFieldValidation.normalizePhone(it) }
    val content = buildContactContent(draft, editing, normalizedPhone)
    val clearedFieldsIgnored = editing != null && (
        didClear(draft.givenName, editing.givenName) ||
            didClear(draft.surname, editing.surname) ||
            didClear(normalizedPhone, editing.phone) ||
            didClear(draft.email, editing.email) ||
            didClear(draft.city, editing.city) ||
            didClear(draft.country, editing.country) ||
            didClear(draft.birthday, editing.birthday)
        )

    val response = try {
        repo.save(
            content = content,
            knownUniqueId = editing?.uniqueId,
            knownVersionTag = editing?.versionTag,
        )
    } catch (e: ForbiddenException) {
        return ContactSaveResult.Forbidden
    } ?: return ContactSaveResult.Failed

    val photoFailed = photo != null &&
        !uploadContactPhoto(repo, response.uniqueId, response.versionTag, photo)

    return ContactSaveResult.Success(photoFailed, clearedFieldsIgnored)
}

/**
 * Persists an edit, routing each piece to the store that survives:
 *
 *  - **Primary fields** (name/phone/email/location/birthday): for an *identity* contact (has an
 *    odinId — enriched on every sync, from the peer's ProfileDrive when connected or their public
 *    profile card otherwise) they go into this app's enrichment-proof override; for a pure manual
 *    contact (no odinId, never synced) they go to content as normal.
 *  - **Additional phones / emails** always go into the override — the contact schema has only one
 *    phone/email slot everywhere, so extras can only live in our app-private blob.
 *
 * [synced] is the baseline (no override applied) used to diff primary fields; [editing] is the
 * displayed entry, used to tell whether an override already exists (so we don't issue a no-op
 * delete). [useOverride] is the connected-and-stored decision made by the caller.
 */
suspend fun saveContactEdit(
    store: ContactOverrideStore,
    repo: ContactRepository,
    useOverride: Boolean,
    editing: ContactBookEntry,
    synced: ContactBookEntry,
    draft: ContactDraft,
    additionalPhones: List<String>,
    additionalEmails: List<String>,
    photo: PlatformFile?,
): ContactSaveResult {
    val cleanPhones = additionalPhones
        .mapNotNull { p -> p.ifBlank { null }?.let { ContactFieldValidation.normalizePhone(it) } }
        .distinct()
    val cleanEmails = additionalEmails
        .map { it.trim() }
        .filter { it.isNotBlank() && ContactFieldValidation.isValidEmail(it) }
        .distinct()
    val hadOverride = editing.syncedOverlay != null ||
        editing.additionalPhones.isNotEmpty() || editing.additionalEmails.isNotEmpty()

    if (useOverride) {
        val versionTag = editing.versionTag ?: return ContactSaveResult.Failed
        val overlay = buildContactOverlay(draft, synced)
            .copy(additionalPhones = cleanPhones, additionalEmails = cleanEmails)
        // Nothing to write and no existing override to clear → skip the override write entirely.
        val newTag = if (overlay.isEmpty && !hadOverride) {
            versionTag
        } else {
            try {
                store.save(editing.uniqueId, versionTag, overlay)
            } catch (e: ForbiddenException) {
                return ContactSaveResult.Forbidden
            } ?: return ContactSaveResult.Failed
        }
        val photoFailed = photo != null && !uploadContactPhoto(repo, editing.uniqueId, newTag, photo)
        return ContactSaveResult.Success(photoFailed = photoFailed)
    }

    // Non-connected: primaries are owner-owned content; only the additions need the override layer.
    val result = saveContactDraft(repo, draft, synced, photo)
    if (result !is ContactSaveResult.Success) return result

    val addOverlay = ContactFieldOverlay(additionalPhones = cleanPhones, additionalEmails = cleanEmails)
    if (!addOverlay.isEmpty || hadOverride) {
        val tag = repo.contacts.value.firstOrNull { it.uniqueId == editing.uniqueId }?.versionTag
        if (tag != null) {
            try {
                store.save(editing.uniqueId, tag, addOverlay)
            } catch (e: ForbiddenException) {
                return ContactSaveResult.Forbidden
            }
        }
    }
    return result
}

private suspend fun uploadContactPhoto(
    repo: ContactRepository,
    uniqueId: Uuid,
    versionTag: Uuid,
    photo: PlatformFile,
): Boolean {
    val bytes = try {
        photo.readBytes()
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (_: Exception) {
        return false
    }
    if (bytes.isEmpty()) return false
    val contentType = photo.contentType()
    return repo.setImage(
        uniqueId = uniqueId,
        bytes = bytes,
        contentType = contentType,
        versionTag = versionTag,
    )
}

// The V2 server merges per-leaf with Coalesce(incoming, existing): a blanked field is "leave
// alone", never cleared. Mirror that here so the optimistic content matches what the drive syncs
// back, instead of a cleared field flashing empty and reappearing.
private fun coalesce(new: String?, old: String?): String? =
    new?.ifBlank { null } ?: old?.ifBlank { null }

private fun didClear(new: String?, old: String?): Boolean = !old.isNullOrBlank() && new.isNullOrBlank()

/** Extracted so a test can assert the JSON that actually goes on the wire. */
internal fun buildContactContent(
    draft: ContactDraft,
    editing: ContactBookEntry?,
    normalizedPhone: String?,
): ContactContent {
    val odinId = draft.odinId.trim().ifBlank { null } ?: editing?.odinId?.ifBlank { null }
    val mergedDisplay = coalesce(draft.displayName, editing?.displayName)
    val mergedGiven = coalesce(draft.givenName, editing?.givenName)
    val mergedSurname = coalesce(draft.surname, editing?.surname)
    val mergedPhone = coalesce(normalizedPhone, editing?.phone)
    val mergedEmail = coalesce(draft.email, editing?.email)
    val mergedCity = coalesce(draft.city, editing?.city)
    val mergedCountry = coalesce(draft.country, editing?.country)
    val mergedBirthday = coalesce(draft.birthday, editing?.birthday)

    return ContactContent(
        odinId = odinId,
        name = ContactName(
            displayName = mergedDisplay,
            givenName = mergedGiven,
            surname = mergedSurname,
        ),
        source = editing?.source ?: ContactBookSource.MANUAL,
        // The edit form only touches city/country; carry the rest of the address from the edited
        // contact so the optimistic content matches what the per-leaf server merge syncs back (the
        // omitted street/postcode/label fields are "leave alone", not "clear").
        location = if (mergedCity != null || mergedCountry != null ||
            !editing?.addressLine1.isNullOrBlank() || !editing?.addressLine2.isNullOrBlank() ||
            !editing?.postcode.isNullOrBlank() || !editing?.locationLabel.isNullOrBlank()
        ) {
            ContactLocation(
                label = editing?.locationLabel?.ifBlank { null },
                addressLine1 = editing?.addressLine1?.ifBlank { null },
                addressLine2 = editing?.addressLine2?.ifBlank { null },
                postcode = editing?.postcode?.ifBlank { null },
                city = mergedCity,
                country = mergedCountry,
            )
        } else null,
        // Named, not positional: `label` sits first on all three, so a positional argument files
        // the number under the label and posts no number at all.
        phone = mergedPhone?.let { ContactPhone(number = it) },
        email = mergedEmail?.let { ContactEmail(email = it) },
        birthday = mergedBirthday?.let { ContactBirthday(date = it) },
    )
}
