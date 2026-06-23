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
    val isEmergencyContact: Boolean = false,
)

/** Whether this contact is one of our emergency contacts, read from our app-data slot. */
fun Contact.isEmergencyContact(): Boolean = chatAppData()?.isEmergencyContact == true

/**
 * Live list of our emergency contacts, derived from [ContactRepository.contacts] via the app-data
 * flag — so it tracks the same optimistic writes and sync reconciliation. Cold flow: collect it
 * (e.g. `collectAsStateWithLifecycle`) or `stateIn` it yourself. Consumers sort.
 */
val ContactRepository.emergencyContacts: Flow<List<Contact>>
    get() = contacts.map { list -> list.filter { it.isEmergencyContact() } }

private fun Contact.chatAppData(): ChatContactAppData? =
    appDataFor(AppConfig.APP_ID)?.let {
        runCatching { OdinSystemSerializer.deserialize<ChatContactAppData>(it) }.getOrNull()
    }

/**
 * Marks the contact as an emergency contact in our app-data slot — a minimal-delta write that merges
 * onto any existing blob ([ContactRepository.setAppData]). Returns the write response, or null on
 * failure; rethrows the same exceptions as [ContactRepository.setAppData].
 */
suspend fun ContactRepository.setEmergencyContact(uniqueId: Uuid, versionTag: Uuid): ContactWriteResponse? =
    writeEmergencyFlag(uniqueId, versionTag, isEmergency = true)

/** Clears the emergency-contact flag in our app-data slot (dropping the slot if it becomes empty). */
suspend fun ContactRepository.clearEmergencyContact(uniqueId: Uuid, versionTag: Uuid): ContactWriteResponse? =
    writeEmergencyFlag(uniqueId, versionTag, isEmergency = false)

private suspend fun ContactRepository.writeEmergencyFlag(
    uniqueId: Uuid,
    versionTag: Uuid,
    isEmergency: Boolean,
): ContactWriteResponse? {
    val current = contacts.value.firstOrNull { it.uniqueId == uniqueId }?.chatAppData()
        ?: ChatContactAppData()
    val updated = current.copy(isEmergencyContact = isEmergency)
    return if (updated == ChatContactAppData()) {
        // All flags back to default — drop the whole slot rather than keep an empty blob.
        deleteAppData(uniqueId, AppConfig.APP_ID, versionTag)
    } else {
        setAppData(uniqueId, AppConfig.APP_ID, OdinSystemSerializer.serialize(updated), versionTag)
    }
}
