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
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.model.ContactBookSource
import id.homebase.core.util.resolveContentType
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
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
    // An odinId may be set on a new contact, or kept from the edited one.
    val odinId = draft.odinId.trim().ifBlank { null } ?: editing?.odinId?.ifBlank { null }

    // The V2 server merges per-leaf with Coalesce(incoming, existing): a blanked field is "leave
    // alone", never cleared. Mirror that here — keep the old value when an edit blanked a
    // previously-set field — so the saved/optimistic content matches what the drive will sync back
    // (no "cleared" field flashing empty then reappearing). `didClear` drives the user warning.
    fun coalesce(new: String?, old: String?): String? = new?.ifBlank { null } ?: old?.ifBlank { null }
    fun didClear(new: String?, old: String?): Boolean = !old.isNullOrBlank() && new.isNullOrBlank()

    val mergedDisplay = coalesce(draft.displayName, editing?.displayName)
    val mergedGiven = coalesce(draft.givenName, editing?.givenName)
    val mergedSurname = coalesce(draft.surname, editing?.surname)
    val mergedPhone = coalesce(normalizedPhone, editing?.phone)
    val mergedEmail = coalesce(draft.email, editing?.email)
    val mergedCity = coalesce(draft.city, editing?.city)
    val mergedCountry = coalesce(draft.country, editing?.country)
    val mergedBirthday = coalesce(draft.birthday, editing?.birthday)

    val clearedFieldsIgnored = editing != null && (
        didClear(draft.givenName, editing.givenName) ||
            didClear(draft.surname, editing.surname) ||
            didClear(normalizedPhone, editing.phone) ||
            didClear(draft.email, editing.email) ||
            didClear(draft.city, editing.city) ||
            didClear(draft.country, editing.country) ||
            didClear(draft.birthday, editing.birthday)
        )

    val content = ContactContent(
        odinId = odinId,
        name = ContactName(
            displayName = mergedDisplay,
            givenName = mergedGiven,
            surname = mergedSurname,
        ),
        source = editing?.source ?: ContactBookSource.MANUAL,
        location = if (mergedCity != null || mergedCountry != null) {
            ContactLocation(city = mergedCity, country = mergedCountry)
        } else null,
        phone = mergedPhone?.let { ContactPhone(it) },
        email = mergedEmail?.let { ContactEmail(it) },
        birthday = mergedBirthday?.let { ContactBirthday(it) },
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
    val contentType = resolveContentType(photo.name, photo.mimeType()?.toString())
    return repo.setImage(
        uniqueId = uniqueId,
        bytes = bytes,
        contentType = contentType,
        versionTag = versionTag,
    )
}
