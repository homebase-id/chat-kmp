@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.contactbook

import co.touchlab.kermit.Logger
import id.homebase.api.client.contacts.Contact
import id.homebase.api.client.contacts.ContactRepository
import id.homebase.api.client.contacts.ContactsProvider
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.core.config.AppConfig
import id.homebase.core.ui.screens.contactbook.model.ContactFieldOverlay
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
class ContactOverrideStore internal constructor(
    eventBus: EventBus,
    private val scope: CoroutineScope,
    private val readOverride: suspend (Contact) -> String?,
    private val writeOverride: suspend (Uuid, Uuid, String?) -> Uuid?,
) {
    constructor(repo: ContactRepository, eventBus: EventBus, scope: CoroutineScope) : this(
        eventBus = eventBus,
        scope = scope,
        readOverride = { repo.loadAppExtData(it, AppConfig.APP_ID) },
        writeOverride = { uniqueId, versionTag, content ->
            val response = if (content == null) repo.deleteAppExtData(uniqueId, versionTag)
            else repo.setAppExtData(uniqueId, content, versionTag)
            response?.versionTag
        },
    )

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true; isLenient = true }

    private val _overrides = MutableStateFlow<Map<Uuid, ContactFieldOverlay>>(emptyMap())
    /** contact uniqueId → its applied override. Absent = no override. */
    val overrides: StateFlow<Map<Uuid, ContactFieldOverlay>> = _overrides.asStateFlow()

    // The contact versionTag we last hydrated, per id: skip a re-fetch while it's unchanged, but
    // re-fetch when the tag advances (the override payload may have changed under us).
    private val hydratedVersion = MutableStateFlow<Map<Uuid, Uuid?>>(emptyMap())
    private val mutex = Mutex()
    private val fetchLimit = Semaphore(MAX_CONCURRENT_FETCHES)

    private class Fetch(val versionTag: Uuid?, val job: Deferred<Unit>)

    private val inFlight = mutableMapOf<Uuid, Fetch>()

    init {
        scope.launch {
            val replayed = eventBus.events.replayCache.size
            eventBus.events.drop(replayed).collect { event ->
                if (event is BackendEvent.SessionEnded) reset()
            }
        }
    }

    // Test seam: the registry is pruned on completion, and nothing else can observe that.
    internal suspend fun inFlightFetchCount(): Int = mutex.withLock { inFlight.size }

    private suspend fun reset() {
        _overrides.value = emptyMap()
        hydratedVersion.value = emptyMap()
        mutex.withLock { inFlight.clear() }
    }

    /**
     * Lazy-load [contact]'s override from the bulk tier when it advertises the payload and we haven't
     * already loaded this exact version. A cheap no-op otherwise, so it's safe to call once per
     * contact whenever the list changes.
     */
    fun hydrate(contact: Contact) {
        if (forgetIfPayloadGone(contact) || isUpToDate(contact)) return
        scope.launch { hydrateJoining(contact) }
    }

    /**
     * [hydrate] for a whole list, suspending until every fetch has landed — for a caller that reads
     * [overrides] on the next line, which the fire-and-forget [hydrate] would leave empty.
     */
    suspend fun hydrateAll(contacts: List<Contact>): Unit = coroutineScope {
        contacts.forEach { contact -> launch { hydrateJoining(contact) } }
    }

    // Awaits a running fetch rather than skipping it — [hydrateAll]'s caller reads `overrides` the
    // line after it returns. Only a fetch for the SAME versionTag: one parked under an older tag
    // would answer with an overlay already known to be stale.
    private suspend fun hydrateJoining(contact: Contact) {
        val id = contact.uniqueId
        val load = mutex.withLock {
            val running = inFlight[id]
                ?.takeIf { it.job.isActive && it.versionTag == contact.versionTag }
            when {
                running != null -> running.job
                forgetIfPayloadGone(contact) || isUpToDate(contact) -> null
                else -> scope.async { fetch(contact) }.also { job ->
                    val entry = Fetch(contact.versionTag, job)
                    inFlight[id] = entry
                    // Or a settled Deferred pins its Contact until SessionEnded.
                    job.invokeOnCompletion {
                        scope.launch {
                            mutex.withLock { if (inFlight[id] === entry) inFlight.remove(id) }
                        }
                    }
                }
            }
        }
        load?.await()
    }

    // On the store's own scope, so a caller that stops waiting (hydrateAll under a timeout) still
    // gets the result cached.
    private suspend fun fetch(contact: Contact) = fetchLimit.withPermit {
        try {
            loadInto(contact)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(tag = TAG, throwable = e) { "override hydrate failed for ${contact.uniqueId}" }
        }
    }

    /** Drops the cached override of a contact that no longer advertises the payload. */
    private fun forgetIfPayloadGone(contact: Contact): Boolean {
        if (ContactsProvider.CONTACT_APP_EXT_DATA_PAYLOAD_KEY in contact.payloadKeys) return false
        val id = contact.uniqueId
        if (id in _overrides.value) _overrides.update { it - id }
        hydratedVersion.update { it + (id to contact.versionTag) }
        return true
    }

    private fun isUpToDate(contact: Contact): Boolean =
        hydratedVersion.value[contact.uniqueId] == contact.versionTag

    private suspend fun loadInto(contact: Contact) {
        val id = contact.uniqueId
        // Claim after the payload lands: claiming first marks an abandoned fetch hydrated for good.
        val overlay = loadOverlay(contact)
        mutex.withLock {
            // Not our contact any more — a newer tag took it, or reset() dropped the session.
            if (inFlight[id]?.versionTag != contact.versionTag) return
            hydratedVersion.update { it + (id to contact.versionTag) }
            _overrides.update {
                if (overlay == null || overlay.isEmpty) it - id else it + (id to overlay)
            }
        }
    }

    private suspend fun loadOverlay(contact: Contact): ContactFieldOverlay? {
        val raw = readOverride(contact) ?: return null
        return runCatching { json.decodeFromString<ContactFieldOverlay>(raw) }.getOrNull()
    }

    /**
     * Persist [overlay] for [uniqueId] (delete when empty), optimistically updating [overrides] so
     * callers see the change at once; the authoritative row lands later via drive sync. [versionTag]
     * must be the contact's current tag. Returns the new versionTag, or null on a generic failure.
     * Rethrows [id.homebase.api.client.ForbiddenException] (403).
     */
    suspend fun save(uniqueId: Uuid, versionTag: Uuid, overlay: ContactFieldOverlay): Uuid? {
        val content = if (overlay.isEmpty) null else json.encodeToString(overlay)
        val newTag = writeOverride(uniqueId, versionTag, content) ?: return null

        // A fetch already in flight read the pre-write blob, and loadInto's guard only compares
        // tags — which this write leaves untouched on the Contact it captured. Drop it, or it
        // lands afterwards and reinstates the overlay the user just replaced.
        mutex.withLock { inFlight.remove(uniqueId) }
        hydratedVersion.update { it + (uniqueId to newTag) }
        _overrides.update { if (overlay.isEmpty) it - uniqueId else it + (uniqueId to overlay) }
        return newTag
    }
}

private const val TAG = "ContactOverrideStore"

/** Each fetch is a header read plus a decrypt; a cold contact book must not open them all at once. */
private const val MAX_CONCURRENT_FETCHES = 4
