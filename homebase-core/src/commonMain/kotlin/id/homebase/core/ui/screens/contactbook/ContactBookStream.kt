@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.contactbook

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.contacts.ContactsProvider
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.core.config.contactTargetDrive
import id.homebase.core.ui.screens.contactbook.model.ContactBookEntry
import id.homebase.core.ui.screens.contactbook.model.toContactBookEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "ContactBookStream"

/**
 * Real-time view of the user's contacts, read from the (mandatory) Contacts
 * drive's local index and kept fresh via [EventBus] — same pattern as
 * [id.homebase.chat.services.convo.contact.DriveContactService], but it produces
 * the richer [ContactBookEntry] (phone/email/location/birthday) the manager UI
 * needs rather than the connection-oriented `ContactUiModel`.
 *
 * Writes do NOT go through this class — they go through
 * [ContactBookService] (api ContactsProvider). After a successful write the
 * ViewModel calls [insertOrUpdateOptimistic] / [removeOptimistic] for instant
 * feedback; the authoritative row lands later via drive sync.
 */
class ContactBookStream(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {
    private val driveId = contactTargetDrive.alias

    private val _contacts = MutableStateFlow<List<ContactBookEntry>>(emptyList())
    val contacts: StateFlow<List<ContactBookEntry>> = _contacts.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    // Resurrection guard: a removed contact must not reappear from a stale batch
    // before the server-confirmed delete syncs down.
    private val deletedIds = mutableSetOf<Uuid>()

    init {
        scope.launch { observeEvents() }
    }

    /** Load from the local DB. Called from onPostAuthenticated, never from init. */
    fun start() {
        scope.launch { loadAll() }
    }

    /** Clear all in-memory state for a clean login as a different identity. */
    fun reset() {
        _contacts.value = emptyList()
        _isLoaded.value = false
        deletedIds.clear()
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
            val entries = result.records
                .mapNotNull { it.toContactBookEntry() }
                .filter { it.uniqueId !in deletedIds }
                // The contact drive can hold >1 row per identity; NewestFirst +
                // distinctBy keeps the freshest per uniqueId.
                .distinctBy { it.uniqueId }
            _contacts.value = entries.sortedBy { it.sortKey }
            Logger.d(tag = TAG) { "loadAll: ${entries.size} contact(s)" }
        } catch (e: Exception) {
            Logger.e(e, TAG) { "Failed to load contacts" }
        }
        _isLoaded.value = true
    }

    private suspend fun observeEvents() {
        eventBus.events.collect { event ->
            when (event) {
                is BackendEvent.SessionEnded -> reset()

                is BackendEvent.DataEvent.BatchReceived -> {
                    if (event.driveId != driveId) return@collect
                    processBatchIncrementally(event.batchData)
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

    private fun processBatchIncrementally(batch: List<HomebaseFile>) {
        for (file in batch) {
            val entry = file.toContactBookEntry() ?: continue
            if (entry.uniqueId in deletedIds) continue
            upsert(entry)
        }
    }

    private fun upsert(entry: ContactBookEntry) {
        _contacts.update { current ->
            val list = current.toMutableList()
            val idx = list.indexOfFirst { it.uniqueId == entry.uniqueId }
            if (idx >= 0) list[idx] = entry else list.add(entry)
            list.sortedBy { it.sortKey }
        }
    }

    /** Optimistically add or replace a contact after a successful write. */
    fun insertOrUpdateOptimistic(entry: ContactBookEntry) {
        deletedIds -= entry.uniqueId
        upsert(entry)
    }

    /** Optimistically remove a contact after a delete is issued. */
    fun removeOptimistic(uniqueId: Uuid) {
        deletedIds += uniqueId
        _contacts.update { current -> current.filterNot { it.uniqueId == uniqueId } }
    }
}
