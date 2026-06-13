@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import co.touchlab.kermit.Logger
import id.homebase.api.client.contacts.ContactContent
import id.homebase.api.client.contacts.ContactWriteResponse
import id.homebase.api.client.contacts.ContactWriteResult
import id.homebase.api.client.contacts.ContactsProvider
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "ContactBookService"

/**
 * Write path for the contact manager. Thin wrapper over the api-layer
 * [ContactsProvider] (the V2 `/api/v2/contacts` controller) — the one write path
 * that supports contacts WITHOUT an odinId, which both device-imported and
 * manually-created contacts need. Server-written contacts sync down to the
 * Contacts drive, where [ContactBookStream] picks them up.
 *
 * All methods return success/failure rather than throwing, so the ViewModel can
 * surface a typed error and revert its optimistic update.
 */
class ContactBookService(
    private val contactsProvider: ContactsProvider,
) {
    /**
     * Create-or-update via the provider's bounded merge-and-retry flow. Pass
     * [knownUniqueId] + [knownVersionTag] for an edit (goes straight to UPDATE);
     * omit them for a new contact (CREATE, falling back to UPDATE on 409).
     * Returns the new uniqueId/versionTag, or null on failure.
     */
    suspend fun save(
        content: ContactContent,
        knownUniqueId: Uuid? = null,
        knownVersionTag: Uuid? = null,
    ): ContactWriteResponse? = try {
        contactsProvider.saveContact(
            content = content,
            knownUniqueId = knownUniqueId,
            knownVersionTag = knownVersionTag,
        )
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(e, TAG) { "saveContact failed" }
        null
    }

    /** Soft-delete. Returns true on success (or already-gone), false on error. */
    suspend fun delete(uniqueId: Uuid): Boolean = try {
        contactsProvider.deleteContact(uniqueId)
        true
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(e, TAG) { "deleteContact failed for $uniqueId" }
        false
    }

    /**
     * Uploads (client-encrypts) an avatar for an existing contact via the provider's
     * version-gated image endpoint. Must be called after [save] so [uniqueId] and
     * [versionTag] are known. Returns true on success.
     */
    suspend fun setPhoto(
        uniqueId: Uuid,
        contactDriveId: Uuid,
        bytes: ByteArray,
        contentType: String,
        versionTag: Uuid,
    ): Boolean = try {
        val result = contactsProvider.setContactImage(
            uniqueId = uniqueId,
            contactDriveId = contactDriveId,
            imageBytes = bytes,
            contentType = contentType,
            versionTag = versionTag,
        )
        result is ContactWriteResult.Ok
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(e, TAG) { "setContactImage failed for $uniqueId" }
        false
    }

    /** Best-effort enrichment of a connected identity from its public profile. */
    suspend fun syncFromIdentity(odinId: String) {
        try {
            contactsProvider.syncContact(id.homebase.api.common.OdinId(odinId))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "syncContact failed for $odinId" }
        }
    }
}
