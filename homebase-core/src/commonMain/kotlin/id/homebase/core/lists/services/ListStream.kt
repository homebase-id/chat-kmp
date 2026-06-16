package id.homebase.core.lists.services

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.core.config.listsLabeledDrive
import id.homebase.core.lists.ListsProtocol
import id.homebase.core.lists.model.ListDefinition
import id.homebase.core.lists.model.ListItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

// ---- pure, testable state (do not change; the test depends on these exact shapes) ----

data class ListRecord(
    val listId: Uuid,
    val fileId: Uuid,
    val versionTag: Uuid?,
    val createdBy: OdinId?,
    val definition: ListDefinition,
)

data class ListItemRecord(
    val itemId: Uuid,
    val listId: Uuid,
    val fileId: Uuid,
    val versionTag: Uuid?,
    val createdBy: OdinId?,
    val item: ListItem,
)

data class ListsData(
    val dataReady: Boolean = false,
    val lists: List<ListRecord> = emptyList(),
)

class ListStreamState {
    private val defs = LinkedHashMap<Uuid, ListRecord>()
    private val items = LinkedHashMap<Uuid, ListItemRecord>()

    fun upsertDefinition(rec: ListRecord) { defs[rec.listId] = rec }
    fun removeDefinition(listId: Uuid) { defs.remove(listId) }
    fun upsertItem(rec: ListItemRecord) { items[rec.itemId] = rec }
    fun removeItem(itemId: Uuid) { items.remove(itemId) }
    /** Drop every item belonging to [listId] — used when a list definition is deleted. */
    fun removeItemsForList(listId: Uuid) { items.entries.removeAll { it.value.listId == listId } }
    fun clear() { defs.clear(); items.clear() }

    fun lists(): List<ListRecord> = defs.values.sortedBy { it.definition.title.lowercase() }

    fun itemsByList(): Map<Uuid, List<ListItemRecord>> =
        items.values.groupBy { it.listId }
            .mapValues { (_, v) -> v.sortedWith(compareBy({ it.item.sortKey }, { it.itemId.toString() })) }
}

// ---- IO shell ----

class ListStream(
    private val credentialsManager: CredentialsManager,
    private val databaseManager: DatabaseManager,
    private val driveFileProvider: DriveFileProvider,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {
    private val listsDrive = listsLabeledDrive.drive.alias
    private val state = ListStreamState()

    // Serializes every mutation of [state] + publish so concurrent loaders/merges (start(),
    // sync-stopped, outbox events, logout) can never interleave clear()+upsert on the maps.
    private val stateMutex = Mutex()

    private val _lists = MutableStateFlow(ListsData(dataReady = false))
    val lists: StateFlow<ListsData> = _lists.asStateFlow()

    private val _itemsByList = MutableStateFlow<Map<Uuid, List<ListItemRecord>>>(emptyMap())
    val itemsByList: StateFlow<Map<Uuid, List<ListItemRecord>>> = _itemsByList.asStateFlow()

    private var started = false

    init {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is BackendEvent.SessionEnded -> reset()
                    is BackendEvent.DriveEvent.Stopped ->
                        if (event.driveId == listsDrive && event.totalCount > 0) loadAll()
                    is BackendEvent.DataEvent.BatchReceived ->
                        if (event.driveId == listsDrive) mergeBatch(event.batchData)
                    is BackendEvent.OutboxEvent.ItemCompleted ->
                        if (event.driveId == listsDrive) refreshFile(event.uniqueId)
                    is BackendEvent.OutboxEvent.ItemFailed ->
                        if (event.driveId == listsDrive) refreshFile(event.uniqueId)
                    else -> Unit
                }
            }
        }
    }

    fun reset() {
        started = false
        // Clear under the same lock as every other mutation. Identity-guarded so that a
        // relogin's freshly-loaded data isn't wiped by a late logout-clear: only clear when
        // there is no active identity (a true logout). On relogin, start()->loadAll() clears
        // and reloads under the lock.
        scope.launch {
            stateMutex.withLock {
                if (credentialsManager.getActiveCredentials() == null) {
                    state.clear()
                    publish(dataReady = false)
                }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        scope.launch { loadAll() }
    }

    private suspend fun loadAll() {
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: return
        // Query outside the lock (it's the slow part) so live BatchReceived merges aren't blocked.
        val records = queryAllPaginated(identityId)
        stateMutex.withLock {
            // Discard if the active identity changed (logout / account switch) while we queried —
            // never publish identity A's lists into identity B's session.
            if (credentialsManager.getActiveCredentials()?.getIdentityId() != identityId) return@withLock
            state.clear()
            records.forEach { parseInto(it) }
            publish(dataReady = true)
        }
    }

    /** Page through ALL Lists files (cursor honoured), so >1000 files are never silently dropped. */
    private suspend fun queryAllPaginated(identityId: Uuid): List<HomebaseFile> {
        val all = mutableListOf<HomebaseFile>()
        var cursor: QueryBatchCursor? = null
        while (true) {
            val result = QueryBatch(identityId).queryBatchAsync(
                dbm = databaseManager,
                driveId = listsDrive,
                noOfItems = 1000,
                cursor = cursor,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.UserDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(ListsProtocol.ListDefinitionFileType, ListsProtocol.ListItemFileType),
            )
            all += result.records
            if (!result.hasMoreRows) break
            cursor = result.cursor
        }
        return all
    }

    private suspend fun mergeBatch(files: List<HomebaseFile>) {
        stateMutex.withLock {
            files.forEach { parseInto(it) }
            publish(dataReady = _lists.value.dataReady)
        }
    }

    /**
     * Reconcile a SINGLE file after its outbox item completes/fails — cheaper than a full
     * loadAll() (the optimistic BatchReceived already merged the change; this just picks up the
     * server-confirmed versionTag, or removes the row if the file is gone).
     */
    private suspend fun refreshFile(uniqueId: Uuid) {
        val file = driveFileProvider.getFileHeaderByUid(listsDrive, uniqueId)
        stateMutex.withLock {
            if (file != null) {
                parseInto(file)
            } else {
                state.removeItem(uniqueId)
                state.removeDefinition(uniqueId)
                state.removeItemsForList(uniqueId)
            }
            publish(dataReady = _lists.value.dataReady)
        }
    }

    /** Decode one indexed file into [state]. content is plaintext JSON. MUST be called under [stateMutex]. */
    private fun parseInto(file: HomebaseFile) {
        val app = file.fileMetadata.appData
        val deleted = file.fileState == FileState.Deleted
        when (app.fileType) {
            ListsProtocol.ListDefinitionFileType -> {
                val listId = app.uniqueId ?: return
                if (deleted) {
                    state.removeDefinition(listId)
                    state.removeItemsForList(listId)   // don't leave the list's items orphaned
                    return
                }
                val def = app.content?.let { runCatching { OdinSystemSerializer.deserialize<ListDefinition>(it) }.getOrNull() } ?: return
                state.upsertDefinition(ListRecord(listId, file.fileId, file.fileMetadata.versionTag, file.fileMetadata.originalAuthor, def))
            }
            ListsProtocol.ListItemFileType -> {
                val itemId = app.uniqueId ?: return
                if (deleted) { state.removeItem(itemId); return }
                val listId = app.groupId ?: return
                val item = app.content?.let { runCatching { OdinSystemSerializer.deserialize<ListItem>(it) }.getOrNull() } ?: return
                state.upsertItem(ListItemRecord(itemId, listId, file.fileId, file.fileMetadata.versionTag, file.fileMetadata.originalAuthor, item))
            }
            else -> Unit
        }
    }

    private fun publish(dataReady: Boolean) {
        _lists.value = ListsData(dataReady = dataReady, lists = state.lists())
        _itemsByList.value = state.itemsByList()
    }
}
