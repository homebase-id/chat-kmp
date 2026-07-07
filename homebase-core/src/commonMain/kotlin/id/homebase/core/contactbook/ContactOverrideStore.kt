@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.contactbook

import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.client.contacts.ContactsProvider
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.core.config.AppConfig
import id.homebase.core.ui.screens.contactbook.model.ContactFieldOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Owns the per-contact **user overrides** of profile-synced fields, shared by the contact list and
 * detail ViewModels so an edit made in one is reflected in the other.
 *
 * Overrides are stored in this app's **bulk** contact app-data tier
 * ([ContactsProvider.CONTACT_APP_EXT_DATA_PAYLOAD_KEY]) — the one store the server's enrichment merge
 * never overwrites. The bulk tier isn't part of the contacts list query, so overrides are loaded
 * on demand ([hydrate]) only for contacts that actually advertise the payload, then cached here.
 *
 * Registered as a singleton.
 */
class ContactOverrideStore(
    private val repo: ContactRepository,
    eventBus: EventBus,
    private val scope: CoroutineScope,
) {
    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true; isLenient = true }

    private val _overrides = MutableStateFlow<Map<Uuid, ContactFieldOverlay>>(emptyMap())
    /** contact uniqueId → its applied override. Absent = no override. */
    val overrides: StateFlow<Map<Uuid, ContactFieldOverlay>> = _overrides.asStateFlow()

    // The contact versionTag we last hydrated, per id: skip a re-fetch while it's unchanged, but
    // re-fetch when the tag advances (the override payload may have changed under us).
    private val hydratedVersion = MutableStateFlow<Map<Uuid, Uuid?>>(emptyMap())
    private val mutex = Mutex()

    init {
        scope.launch {
            val replayed = eventBus.events.replayCache.size
            eventBus.events.drop(replayed).collect { event ->
                if (event is BackendEvent.SessionEnded) reset()
            }
        }
    }

    private fun reset() {
        _overrides.value = emptyMap()
        hydratedVersion.value = emptyMap()
    }

    /**
     * Lazy-load [contact]'s override from the bulk tier when it advertises the payload and we haven't
     * already loaded this exact version. A cheap no-op otherwise, so it's safe to call once per
     * contact whenever the list changes.
     */
    fun hydrate(contact: Contact) {
        val id = contact.uniqueId
        if (ContactsProvider.CONTACT_APP_EXT_DATA_PAYLOAD_KEY !in contact.payloadKeys) {
            // No bulk payload: forget any stale override cached for this id.
            if (id in _overrides.value) _overrides.update { it - id }
            hydratedVersion.update { it + (id to contact.versionTag) }
            return
        }
        if (hydratedVersion.value[id] == contact.versionTag) return
        scope.launch {
            mutex.withLock {
                if (hydratedVersion.value[id] == contact.versionTag) return@launch
                hydratedVersion.update { it + (id to contact.versionTag) }
            }
            val overlay = loadOverlay(contact)
            _overrides.update {
                if (overlay == null || overlay.isEmpty) it - id else it + (id to overlay)
            }
        }
    }

    private suspend fun loadOverlay(contact: Contact): ContactFieldOverlay? {
        val raw = repo.loadAppExtData(contact, AppConfig.APP_ID) ?: return null
        return runCatching { json.decodeFromString<ContactFieldOverlay>(raw) }.getOrNull()
    }

    /**
     * Persist [overlay] for [uniqueId] (delete when empty), optimistically updating [overrides] so
     * callers see the change at once; the authoritative row lands later via drive sync. [versionTag]
     * must be the contact's current tag. Returns the new versionTag, or null on a generic failure.
     * Rethrows [id.homebase.api.client.ForbiddenException] (403).
     */
    suspend fun save(uniqueId: Uuid, versionTag: Uuid, overlay: ContactFieldOverlay): Uuid? {
        val response = if (overlay.isEmpty) {
            repo.deleteAppExtData(uniqueId, versionTag)
        } else {
            repo.setAppExtData(uniqueId, json.encodeToString(overlay), versionTag)
        } ?: return null

        hydratedVersion.update { it + (uniqueId to response.versionTag) }
        _overrides.update { if (overlay.isEmpty) it - uniqueId else it + (uniqueId to overlay) }
        return response.versionTag
    }
}
