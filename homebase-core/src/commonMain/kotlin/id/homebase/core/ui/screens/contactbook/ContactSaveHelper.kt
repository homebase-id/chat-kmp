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
    /** Saved; push [entry] into the stream. [photoFailed] = contact saved but avatar upload failed. */
    data class Success(val entry: ContactBookEntry, val photoFailed: Boolean) : ContactSaveResult

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

    val display = draft.displayName.ifBlank { normalizedPhone ?: draft.email }.ifBlank { "?" }
    val entry = (editing ?: ContactBookEntry(
        uniqueId = response.uniqueId,
        fileId = response.uniqueId,
        versionTag = response.versionTag,
        displayName = display,
    )).copy(
        uniqueId = response.uniqueId,
        versionTag = response.versionTag,
        odinId = odinId,
        displayName = display,
        givenName = draft.givenName.ifBlank { null },
        surname = draft.surname.ifBlank { null },
        phone = normalizedPhone,
        email = draft.email.ifBlank { null },
        city = draft.city.ifBlank { null },
        country = draft.country.ifBlank { null },
        birthday = draft.birthday.ifBlank { null },
        source = content.source,
    )
    return ContactSaveResult.Success(entry, photoFailed)
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
