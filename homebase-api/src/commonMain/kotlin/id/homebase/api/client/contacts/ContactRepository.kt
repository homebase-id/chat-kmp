@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.client.contacts

import co.touchlab.kermit.Logger
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "ContactRepository"

/**
 * Single source of truth for contacts: reads the (mandatory) Contacts drive as a live list of the
 * server-shaped [Contact] domain model, and writes through the V2 [ContactsProvider]
 * (create/update/delete/sync/image). Consumers map [Contact] into their own UI models — the
 * repository never knows about UI.
 *
 * Because one object owns both the read state and the writes, writes apply an optimistic update to
 * [contacts] directly; the authoritative row lands later via drive sync (EventBus), which
 * reconciles. Registered as a singleton.
 */
class ContactRepository(
    private val contactsProvider: ContactsProvider,
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {
    private val driveId = SystemDriveConstants.contactDrive.alias

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    /** Live contacts, freshest-row-per-uniqueId, in drive order (NewestFirst). Consumers sort. */
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    // Resurrection guard: a removed contact must not reappear from a stale batch before the
    // server-confirmed delete syncs down.
    private val deletedIds = mutableSetOf<Uuid>()

    // Serializes loadAll so concurrent ensureLoaded() callers don't run overlapping queries.
    private val loadMutex = Mutex()

    init {
        scope.launch { observeEvents() }
    }

    // ------------------------------------------------------------
    // Lifecycle / read
    // ------------------------------------------------------------

    /** Load from the local DB. Called from the post-auth bootstrap. */
    fun start() {
        scope.launch { loadAll() }
    }

    /** Clear all in-memory state for a clean login as a different identity. */
    fun reset() {
        _contacts.value = emptyList()
        _isLoaded.value = false
        deletedIds.clear()
    }

    /**
     * Guarantees the list has been loaded at least once this session, on demand — so a screen
     * never depends on the post-auth bootstrap having run. Idempotent and cheap once loaded.
     */
    suspend fun ensureLoaded() {
        if (_isLoaded.value) return
        loadMutex.withLock {
            if (_isLoaded.value) return
            loadAll()
        }
    }

    suspend fun loadAll() {
        val creds = credentialsManager.getActiveCredentials() ?: run {
            _isLoaded.value = true
            return
        }
        try {
            val result = QueryBatch(creds.getIdentityId()).queryBatchAsync(
                dbm = databaseManager,
                driveId = driveId,
                noOfItems = 1000,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(ContactsProvider.CONTACT_FILE_TYPE),
            )
            // The drive can hold >1 row per identity; NewestFirst + distinctBy keeps the freshest.
            _contacts.value = result.records
                .mapNotNull { it.toContact() }
                .filter { it.uniqueId !in deletedIds }
                .distinctBy { it.uniqueId }
            Logger.d(tag = TAG) { "loadAll: ${_contacts.value.size} contact(s)" }
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to load contacts" }
        }
        _isLoaded.value = true
    }

    private suspend fun observeEvents() {
        // EventBus replays its last event (replay=1) to new subscribers; a stale replayed
        // SessionEnded would reset() and race loadAll(). Skip whatever is already buffered and act
        // on live events only.
        val replayed = eventBus.events.replayCache.size
        eventBus.events.drop(replayed).collect { event ->
            when (event) {
                is BackendEvent.SessionEnded -> reset()

                is BackendEvent.DataEvent.BatchReceived -> {
                    if (event.driveId != driveId) return@collect
                    for (file in event.batchData) {
                        val contact = file.toContact() ?: continue
                        if (contact.uniqueId in deletedIds) continue
                        upsert(contact)
                    }
                }

                is BackendEvent.DriveEvent.Stopped -> {
                    if (event.driveId != driveId) return@collect
                    if (event.totalCount > 0) {
                        try {
                            loadAll()
                        } catch (e: Exception) {
                            Logger.e(e, TAG) { "post-Stopped reload failed: ${e.message}" }
                        }
                    }
                }

                else -> {}
            }
        }
    }

    private fun upsert(contact: Contact) {
        _contacts.update { current ->
            val idx = current.indexOfFirst { it.uniqueId == contact.uniqueId }
            if (idx >= 0) current.toMutableList().apply { this[idx] = contact }
            else current + contact
        }
    }

    // ------------------------------------------------------------
    // Write (V2 controller) — each applies an optimistic update
    // ------------------------------------------------------------

    /**
     * Create-or-update via the provider's bounded merge-and-retry flow. Pass [knownUniqueId] +
     * [knownVersionTag] for an edit (UPDATE), omit for a new contact (CREATE→UPDATE on 409).
     * Returns the new id/versionTag, or null on a generic failure. Rethrows [ForbiddenException]
     * (403) so callers can explain the missing-permission cause.
     */
    suspend fun save(
        content: ContactContent,
        knownUniqueId: Uuid? = null,
        knownVersionTag: Uuid? = null,
    ): ContactWriteResponse? {
        val response = try {
            contactsProvider.saveContact(content, knownUniqueId, knownVersionTag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ForbiddenException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "saveContact failed" }
            return null
        }

        deletedIds -= response.uniqueId
        val existingImage = _contacts.value.firstOrNull { it.uniqueId == response.uniqueId }?.image
        upsert(Contact(response.uniqueId, response.versionTag, content, existingImage))
        return response
    }

    /**
     * Soft-delete. Optimistically removes the contact; on a generic failure it reloads to restore
     * truth. Returns true on success (or already-gone). Rethrows [ForbiddenException] (403).
     */
    suspend fun delete(uniqueId: Uuid): Boolean {
        deletedIds += uniqueId
        _contacts.update { current -> current.filterNot { it.uniqueId == uniqueId } }
        return try {
            contactsProvider.deleteContact(uniqueId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ForbiddenException) {
            deletedIds -= uniqueId
            loadAll()
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "deleteContact failed for $uniqueId" }
            deletedIds -= uniqueId
            loadAll()
            false
        }
    }

    /** Best-effort server-side enrichment of a connected identity from its public profile. */
    suspend fun sync(odinId: OdinId) {
        try {
            contactsProvider.syncContact(odinId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "syncContact failed for $odinId" }
        }
    }

    /**
     * Uploads (client-encrypts) an avatar for an existing contact via the provider's version-gated
     * image endpoint. Returns true on success. The updated row (with the image payload) lands via
     * drive sync.
     */
    suspend fun setImage(
        uniqueId: Uuid,
        bytes: ByteArray,
        contentType: String,
        versionTag: Uuid,
    ): Boolean = try {
        contactsProvider.setContactImage(
            uniqueId = uniqueId,
            contactDriveId = driveId,
            imageBytes = bytes,
            contentType = contentType,
            versionTag = versionTag,
        ) is ContactWriteResult.Ok
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(e, TAG) { "setContactImage failed for $uniqueId" }
        false
    }
}
