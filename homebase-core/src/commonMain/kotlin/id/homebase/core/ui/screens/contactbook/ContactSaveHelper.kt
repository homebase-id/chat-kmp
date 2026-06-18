@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.contacts.ContactBirthday
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.client.contacts.ContactEmail
import id.homebase.api.client.contacts.ContactLocation
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.contacts.ContactName
import id.homebase.api.client.contacts.ContactPhone
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
     * Saved; push [entry] into the stream. [photoFailed] = contact saved but avatar upload failed.
     * [clearedFieldsIgnored] = the edit blanked a previously-set field, which the V2 server merge
     * cannot express (empty = "leave existing alone") — those fields were kept, so the UI should
     * tell the user clearing isn't supported yet.
     */
    data class Success(
        val entry: ContactBookEntry,
        val photoFailed: Boolean,
        val clearedFieldsIgnored: Boolean = false,
    ) : ContactSaveResult

    /** Server rejected the write with 403 — the app token lacks the manage-contacts permission. */
    data object Forbidden : ContactSaveResult

    /** Any other failure (transport, 5xx, contention). */
    data object Failed : ContactSaveResult
}

/**
 * Builds [ContactContent] from a [draft], saves it via [service], uploads the [photo]
 * (if any) after the contact exists, and returns the optimistic [ContactBookEntry] for
 * the caller to push into its stream. Shared by the contact-list and contact-detail
 * ViewModels so edit behaves identically in both. Distinguishes a 403 (Forbidden) so the
 * UI can explain the missing-permission cause rather than a generic failure.
 */
suspend fun saveContactDraft(
    service: ContactBookService,
    draft: ContactDraft,
    editing: ContactBookEntry?,
    photo: PlatformFile?,
    contactDriveId: Uuid,
): ContactSaveResult {
    if (!draft.isSavable) return ContactSaveResult.Failed

    val normalizedPhone = draft.phone.ifBlank { null }
        ?.let { ContactFieldValidation.normalizePhone(it) }
    // An odinId may be set on a new contact, or kept from the edited one.
    val odinId = draft.odinId.trim().ifBlank { null } ?: editing?.odinId?.ifBlank { null }
    val hasLocation = draft.city.isNotBlank() || draft.country.isNotBlank()

    val content = ContactContent(
        odinId = odinId,
        name = ContactName(
            displayName = draft.displayName.ifBlank { null },
            givenName = draft.givenName.ifBlank { null },
            surname = draft.surname.ifBlank { null },
        ),
        source = editing?.source ?: ContactBookSource.MANUAL,
        location = if (hasLocation) {
            ContactLocation(city = draft.city.ifBlank { null }, country = draft.country.ifBlank { null })
        } else null,
        phone = normalizedPhone?.let { ContactPhone(it) },
        email = draft.email.ifBlank { null }?.let { ContactEmail(it) },
        birthday = draft.birthday.ifBlank { null }?.let { ContactBirthday(it) },
    )

    val response = try {
        service.save(
            content = content,
            knownUniqueId = editing?.uniqueId,
            knownVersionTag = editing?.versionTag,
        )
    } catch (e: ForbiddenException) {
        return ContactSaveResult.Forbidden
    } ?: return ContactSaveResult.Failed

    val photoFailed = photo != null &&
        !uploadContactPhoto(service, response.uniqueId, response.versionTag, photo, contactDriveId)

    // The server merges per-leaf with Coalesce(incoming, existing): a blanked field is "leave
    // alone", never cleared. Mirror that in the optimistic entry (keep the old value when the edit
    // blanked a previously-set field) so the UI matches what the drive will sync back — otherwise a
    // "cleared" field would flash empty and then reappear. `cleared` also drives the user warning.
    fun coalesce(new: String?, old: String?): String? = new?.ifBlank { null } ?: old?.ifBlank { null }
    fun didClear(new: String?, old: String?): Boolean =
        !old.isNullOrBlank() && new.isNullOrBlank()

    val mergedGiven = coalesce(draft.givenName, editing?.givenName)
    val mergedSurname = coalesce(draft.surname, editing?.surname)
    val mergedPhone = coalesce(normalizedPhone, editing?.phone)
    val mergedEmail = coalesce(draft.email, editing?.email)
    val mergedCity = coalesce(draft.city, editing?.city)
    val mergedCountry = coalesce(draft.country, editing?.country)
    val mergedBirthday = coalesce(draft.birthday, editing?.birthday)
    val mergedDisplay = coalesce(draft.displayName, editing?.displayName)
        ?: mergedPhone ?: mergedEmail ?: odinId ?: "?"

    val clearedFieldsIgnored = editing != null && (
        didClear(draft.givenName, editing.givenName) ||
            didClear(draft.surname, editing.surname) ||
            didClear(normalizedPhone, editing.phone) ||
            didClear(draft.email, editing.email) ||
            didClear(draft.city, editing.city) ||
            didClear(draft.country, editing.country) ||
            didClear(draft.birthday, editing.birthday)
        )

    val entry = (editing ?: ContactBookEntry(
        uniqueId = response.uniqueId,
        fileId = response.uniqueId,
        versionTag = response.versionTag,
        displayName = mergedDisplay,
    )).copy(
        uniqueId = response.uniqueId,
        versionTag = response.versionTag,
        odinId = odinId,
        displayName = mergedDisplay,
        givenName = mergedGiven,
        surname = mergedSurname,
        phone = mergedPhone,
        email = mergedEmail,
        city = mergedCity,
        country = mergedCountry,
        birthday = mergedBirthday,
        source = content.source,
    )
    return ContactSaveResult.Success(entry, photoFailed, clearedFieldsIgnored)
}

private suspend fun uploadContactPhoto(
    service: ContactBookService,
    uniqueId: Uuid,
    versionTag: Uuid,
    photo: PlatformFile,
    contactDriveId: Uuid,
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
    return service.setPhoto(
        uniqueId = uniqueId,
        contactDriveId = contactDriveId,
        bytes = bytes,
        contentType = contentType,
        versionTag = versionTag,
    )
}
