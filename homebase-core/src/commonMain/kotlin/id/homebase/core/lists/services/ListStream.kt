package id.homebase.core.lists.services

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.core.config.listsLabeledDrive
import id.homebase.core.lists.ListsProtocol
import id.homebase.core.lists.model.ListDefinition
import id.homebase.core.lists.model.ListItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {
    private val listsDrive = listsLabeledDrive.drive.alias
    private val state = ListStreamState()

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
                        if (event.driveId == listsDrive) loadAll()
                    is BackendEvent.OutboxEvent.ItemFailed ->
                        if (event.driveId == listsDrive) loadAll()
                    else -> Unit
                }
            }
        }
    }

    fun reset() {
        started = false
        state.clear()
        publish(dataReady = false)
    }

    fun start() {
        if (started) return
        started = true
        scope.launch { loadAll() }
    }

    private suspend fun loadAll() {
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: return
        state.clear()
        val result = QueryBatch(identityId).queryBatchAsync(
            dbm = databaseManager,
            driveId = listsDrive,
            noOfItems = 1000,
            cursor = null,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.UserDate,
            fileSystemType = 0,
            filetypesAnyOf = listOf(ListsProtocol.ListDefinitionFileType, ListsProtocol.ListItemFileType),
        )
        result.records.forEach { parseInto(it) }
        publish(dataReady = true)
    }

    private fun mergeBatch(files: List<HomebaseFile>) {
        files.forEach { parseInto(it) }
        publish(dataReady = _lists.value.dataReady)
    }

    private fun parseInto(file: HomebaseFile) {
        val app = file.fileMetadata.appData
        val deleted = file.fileState == FileState.Deleted
        when (app.fileType) {
            ListsProtocol.ListDefinitionFileType -> {
                val listId = app.uniqueId ?: return
                if (deleted) { state.removeDefinition(listId); return }
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
