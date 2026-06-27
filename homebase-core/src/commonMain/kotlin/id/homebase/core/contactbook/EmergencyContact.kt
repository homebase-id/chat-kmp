@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.contactbook

import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.client.contacts.ContactWriteResponse
import id.homebase.api.client.contacts.appDataFor
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.config.AppConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * This chat app's private per-contact app-data blob, stored in the contact's inline app-data slot
 * keyed by [AppConfig.APP_ID] — NOT on the shared [id.homebase.api.client.contacts.ContactContent].
 *
 * Owner-only flags belong here so they can be set AND cleared independently of the contact's shared
 * profile fields: a clear just removes the slot, which the ContactContent field merge can't express
 * for a boolean (an omitted/`false` field reads as "leave alone"). Add new flags here (defaulting to
 * their "unset" value) rather than minting new app-data slots; an all-default blob is dropped.
 */
@Serializable
data class ChatContactAppData(
    /**
     * Whether *we* can locate this contact in an emergency — i.e. they added us to their emergency
     * circle, granting our identity `ConditionalTemporalRead` on their location drive. Set on receipt
     * of their `EmergencyContactDesignated` status message; this flag is the cheap reactive cache,
     * the authoritative check is a temporal-access preflight against the peer.
     */
    val iCanLocate: Boolean = false,
)

/** Whether we can locate this contact (the cached [ChatContactAppData.iCanLocate] flag). */
fun Contact.iCanLocate(): Boolean = chatAppData()?.iCanLocate == true

/**
 * Live list of the contacts we can locate, derived from [ContactRepository.contacts] via the app-data
 * flag — so it tracks the same optimistic writes and sync reconciliation. Cold flow: collect it
 * (e.g. `collectAsStateWithLifecycle`) or `stateIn` it yourself. Consumers sort.
 */
val ContactRepository.locatableContacts: Flow<List<Contact>>
    get() = contacts.map { list -> list.filter { it.iCanLocate() } }

private fun Contact.chatAppData(): ChatContactAppData? =
    appDataFor(AppConfig.APP_ID)?.let {
        runCatching { OdinSystemSerializer.deserialize<ChatContactAppData>(it) }.getOrNull()
    }

/**
 * Marks that we can locate the contact in our app-data slot — a minimal-delta write that merges onto
 * any existing blob ([ContactRepository.setAppData]). Returns the write response, or null on failure;
 * rethrows the same exceptions as [ContactRepository.setAppData].
 */
suspend fun ContactRepository.setICanLocate(uniqueId: Uuid, versionTag: Uuid): ContactWriteResponse? =
    writeICanLocateFlag(uniqueId, versionTag, canLocate = true)

/** Clears the can-locate flag in our app-data slot (dropping the slot if it becomes empty). */
suspend fun ContactRepository.clearICanLocate(uniqueId: Uuid, versionTag: Uuid): ContactWriteResponse? =
    writeICanLocateFlag(uniqueId, versionTag, canLocate = false)

private suspend fun ContactRepository.writeICanLocateFlag(
    uniqueId: Uuid,
    versionTag: Uuid,
    canLocate: Boolean,
): ContactWriteResponse? {
    val current = contacts.value.firstOrNull { it.uniqueId == uniqueId }?.chatAppData()
        ?: ChatContactAppData()
    val updated = current.copy(iCanLocate = canLocate)
    return if (updated == ChatContactAppData()) {
        // All flags back to default — drop the whole slot rather than keep an empty blob.
        deleteAppData(uniqueId, AppConfig.APP_ID, versionTag)
    } else {
        setAppData(uniqueId, AppConfig.APP_ID, OdinSystemSerializer.serialize(updated), versionTag)
    }
}
