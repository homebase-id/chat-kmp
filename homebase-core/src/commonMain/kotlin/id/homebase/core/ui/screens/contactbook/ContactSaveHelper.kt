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
    /** Saved. [photoFailed] = contact saved but avatar upload failed. The repository has already
     *  applied the optimistic update to its `contacts` flow, so callers don't push anything. */
    data class Success(val photoFailed: Boolean) : ContactSaveResult

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

    return ContactSaveResult.Success(photoFailed)
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
