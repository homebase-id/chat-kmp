@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import co.touchlab.kermit.Logger
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.contacts.ContactBirthday
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.client.contacts.ContactEmail
import id.homebase.api.client.contacts.ContactLocation
import id.homebase.api.client.contacts.ContactName
import id.homebase.api.client.contacts.ContactPhone
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.contactcard.ContactCardDescriptor
import id.homebase.core.contactbook.ContactOverrideStore
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.model.ContactBookSource
import id.homebase.core.ui.screens.contactbook.model.ContactFieldOverlay
import id.homebase.core.util.contentType
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "ContactSaveHelper"

/** Outcome of [saveContactDraft]. */
sealed interface ContactSaveResult {
    /**
     * Saved. [photoFailed] = contact saved but avatar upload failed. [additionsFailed] = contact
     * saved but its extra phones/emails could not be attached. [clearedFieldsIgnored] = the
     * edit blanked a previously-set field, which the V2 server merge can't express (empty = "leave
     * existing alone"), so that field was kept — the UI should tell the user clearing isn't
     * supported yet. The repository already applied the optimistic update, so callers push nothing.
     * [uniqueId]/[versionTag] let a caller layer a follow-up write without value-matching the
     * contact back out of the list.
     */
    data class Success(
        val photoFailed: Boolean,
        val clearedFieldsIgnored: Boolean = false,
        val additionsFailed: Boolean = false,
        val uniqueId: Uuid? = null,
        val versionTag: Uuid? = null,
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

    return ContactSaveResult.Success(
        photoFailed = photoFailed,
        clearedFieldsIgnored = clearedFieldsIgnored,
        uniqueId = response.uniqueId,
        versionTag = response.versionTag,
    )
}

/**
 * Creates a contact from [draft] *with* the values the contact schema can't hold — its extra
 * phones/emails and its organization. Those can only live in this app's override blob, which needs
 * the new contact's id, hence the two writes. Ordered create → overlay → photo so each write
 * carries the versionTag the one before it produced.
 */
suspend fun saveNewContact(
    store: ContactOverrideStore,
    repo: ContactRepository,
    draft: ContactDraft,
    additionalPhones: List<String>,
    additionalEmails: List<String>,
    photo: PlatformFile?,
): ContactSaveResult = saveNewContact(
    draft = draft,
    additionalPhones = additionalPhones,
    additionalEmails = additionalEmails,
    photo = photo,
    createContact = { d, p -> saveContactDraft(repo, d, null, p) },
    saveOverlay = store::save,
    uploadPhoto = { uniqueId, versionTag, file -> uploadContactPhoto(repo, uniqueId, versionTag, file) },
)

// Same ordering with the three writes injected, so the contract is assertable without a
// ContactRepository.
internal suspend fun saveNewContact(
    draft: ContactDraft,
    additionalPhones: List<String>,
    additionalEmails: List<String>,
    photo: PlatformFile?,
    createContact: suspend (ContactDraft, PlatformFile?) -> ContactSaveResult,
    saveOverlay: suspend (Uuid, Uuid, ContactFieldOverlay) -> Uuid?,
    uploadPhoto: suspend (Uuid, Uuid, PlatformFile) -> Boolean,
): ContactSaveResult {
    val overlay = additionsOverlay(additionalPhones, additionalEmails, draft.organization)
    if (overlay.isEmpty) return createContact(draft, photo)

    val created = createContact(draft, null)
    if (created !is ContactSaveResult.Success) return created
    val uniqueId = created.uniqueId
    val versionTag = created.versionTag
    if (uniqueId == null || versionTag == null) return created.copy(additionsFailed = true)

    // Every failure past this point is partial, never retryable: the contact is already written, so
    // Failed would invite a retry that creates it twice. Hence nothing narrower than Exception.
    val overlayTag = try {
        saveOverlay(uniqueId, versionTag, overlay)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(e, TAG) { "contact $uniqueId created but its extra values could not be attached" }
        null
    }
    val tag = overlayTag ?: versionTag
    val photoFailed = photo != null && !uploadPhoto(uniqueId, tag, photo)
    return ContactSaveResult.Success(
        photoFailed = photoFailed,
        additionsFailed = overlayTag == null,
        uniqueId = uniqueId,
        versionTag = tag,
    )
}

/** The extras the contact schema can't hold, cleaned to the same rules the editor validates on. */
fun additionsOverlay(
    additionalPhones: List<String>,
    additionalEmails: List<String>,
    organization: String = "",
): ContactFieldOverlay = ContactFieldOverlay(
    additionalPhones = additionalPhones
        .map { ContactFieldValidation.normalizePhone(it) }
        .filter { it.isNotBlank() && ContactFieldValidation.isValidPhone(it) }
        .distinct(),
    additionalEmails = additionalEmails
        .map { it.trim() }
        .filter { it.isNotBlank() && ContactFieldValidation.isValidEmail(it) }
        .distinct(),
    // Capped here, not just on the way out: a stored value over the cap would make the re-shared
    // card fail ContactCardDescriptor.isValid() and vanish from the picker.
    organization = organization.trim()
        .truncateToCodePoints(ContactCardDescriptor.MAX_NAME_CODEPOINTS)
        .ifBlank { null },
)

/**
 * Persists an edit, routing each piece to the store that survives:
 *
 *  - **Primary fields** (name/phone/email/location/birthday): for an *identity* contact (has an
 *    odinId — enriched on every sync, from the peer's ProfileDrive when connected or their public
 *    profile card otherwise) they go into this app's enrichment-proof override; for a pure manual
 *    contact (no odinId, never synced) they go to content as normal.
 *  - **Additional phones / emails and the organization** always go into the override — the contact
 *    schema has one phone/email slot and no organization at all, so these can only live in our
 *    app-private blob.
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
    // Silently drops an invalid extra. Safe only while ContactEditSheet gates Save on the same
    // ContactFieldValidation predicates — otherwise a legacy non-E.164 extra is lost on edit.
    val additions = additionsOverlay(additionalPhones, additionalEmails, draft.organization)
    val cleanPhones = additions.additionalPhones
    val cleanEmails = additions.additionalEmails
    val hadOverride = editing.syncedOverlay != null || editing.organization != null ||
        editing.additionalPhones.isNotEmpty() || editing.additionalEmails.isNotEmpty()

    if (useOverride) {
        val versionTag = editing.versionTag ?: return ContactSaveResult.Failed
        val overlay = buildContactOverlay(draft, synced).copy(
            additionalPhones = cleanPhones,
            additionalEmails = cleanEmails,
            organization = additions.organization,
        )
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

    return attachEditAdditions(
        result = result,
        additions = additions,
        hadOverride = hadOverride,
        currentTag = repo.contacts.value.firstOrNull { it.uniqueId == editing.uniqueId }?.versionTag,
        saveOverlay = { tag, overlay -> store.save(editing.uniqueId, tag, overlay) },
    )
}

/**
 * Layers [additions] onto an already-written contact and folds the outcome into [result]. The
 * write is injected so the contract is assertable without a [ContactOverrideStore].
 *
 * [saveOverlay] reports a generic write failure as null, the same convention as
 * [ContactOverrideStore.save]; that has to reach the caller as [ContactSaveResult.Success
 * .additionsFailed], or the UI says "Contact saved" after every extra phone, extra email and the
 * organization went nowhere. Same for a missing [currentTag] — no tag, no write, same loss.
 */
internal suspend fun attachEditAdditions(
    result: ContactSaveResult.Success,
    additions: ContactFieldOverlay,
    hadOverride: Boolean,
    currentTag: Uuid?,
    saveOverlay: suspend (Uuid, ContactFieldOverlay) -> Uuid?,
): ContactSaveResult {
    if (additions.isEmpty && !hadOverride) return result
    if (currentTag == null) return result.copy(additionsFailed = true)
    val written = try {
        saveOverlay(currentTag, additions)
    } catch (e: ForbiddenException) {
        return ContactSaveResult.Forbidden
    }
    return if (written == null) result.copy(additionsFailed = true) else result
}

private suspend fun uploadContactPhoto(
    repo: ContactRepository,
    uniqueId: Uuid,
    versionTag: Uuid,
    photo: PlatformFile,
): Boolean {
    val bytes = try {
        photo.readBytes()
    } catch (e: CancellationException) {
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
