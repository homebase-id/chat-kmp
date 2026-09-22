@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.webdrop

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileStateFilter
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.core.config.webDropLabeledDrive
import id.homebase.core.ui.screens.webdrop.model.DropRow
import id.homebase.core.ui.screens.webdrop.model.DropStatus
import id.homebase.core.ui.screens.webdrop.model.dropStatusOf
import id.homebase.core.ui.screens.webdrop.model.toReceiptContent
import id.homebase.core.webdrop.WebDropProtocol
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "WebDropStream"

/**
 * The drops list, live. Joins receipts (name, files, link) with their drop files (status) by
 * groupId = dropId. The drop-file query deliberately includes soft-deleted rows: a tombstone is
 * how an expired or burned drop announces itself, and Removed is a state the user asked to see.
 */
class WebDropStream(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {
    private val driveId = webDropLabeledDrive.drive.alias

    private val _drops = MutableStateFlow<List<DropRow>>(emptyList())
    val drops: StateFlow<List<DropRow>> = _drops.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val clearedReceiptIds = mutableSetOf<Uuid>()

    init {
        scope.launch { observeEvents() }
    }

    fun start() {
        scope.launch { loadAll() }
    }

    fun reset() {
        _drops.value = emptyList()
        _isLoaded.value = false
        clearedReceiptIds.clear()
    }

    suspend fun loadAll() {
        val creds = credentialsManager.getActiveCredentials() ?: run {
            _isLoaded.value = true
            return
        }
        val queryBatch = QueryBatch(creds.getIdentityId())

        try {
            val result = queryBatch.queryBatchAsync(
                dbm = databaseManager,
                driveId = driveId,
                noOfItems = 1100,
                sortOrder = QueryBatchSortOrder.NewestFirst,
                sortField = QueryBatchSortField.CreatedDate,
                fileSystemType = FileSystemType.Standard.value,
                fileState = FileStateFilter.All,
                filetypesAnyOf = listOf(WebDropProtocol.DropFileType, WebDropProtocol.ReceiptFileType),
            )

            val (dropFiles, receiptFiles) = result.records.partition {
                it.fileMetadata.appData.fileType == WebDropProtocol.DropFileType
            }
            val dropsByGroup = dropFiles.associateBy { it.fileMetadata.appData.groupId }

            val rows = receiptFiles.mapNotNull { receiptFile ->
                if (receiptFile.isSoftDeleted()) return@mapNotNull null
                val dropId = receiptFile.fileMetadata.appData.groupId ?: return@mapNotNull null
                if (receiptFile.fileId in clearedReceiptIds) return@mapNotNull null
                val receipt = receiptFile.toReceiptContent() ?: return@mapNotNull null
                val dropFile = dropsByGroup[dropId]
                DropRow(
                    dropId = dropId,
                    receiptFileId = receiptFile.fileId,
                    dropFileId = dropFile?.fileId,
                    receipt = receipt,
                    status = dropStatusOf(dropFile, receipt.ttl),
                )
            }

            _drops.value = rows
            _isLoaded.value = true
        } catch (e: Exception) {
            Logger.e(e, TAG) { "loadAll failed" }
            _isLoaded.value = true
        }
    }

    fun removeOptimistic(dropId: Uuid) {
        _drops.update { rows ->
            rows.map { if (it.dropId == dropId) it.copy(status = DropStatus.Removed) else it }
        }
    }

    fun clearOptimistic(receiptFileId: Uuid) {
        clearedReceiptIds += receiptFileId
        _drops.update { rows -> rows.filterNot { it.receiptFileId == receiptFileId } }
    }

    private suspend fun observeEvents() {
        eventBus.events.collect { event ->
            when (event) {
                is BackendEvent.DataEvent.BatchReceived ->
                    if (event.driveId == driveId) loadAll()
                is BackendEvent.DriveEvent.Stopped ->
                    if (event.driveId == driveId && event.totalCount > 0) loadAll()
                is BackendEvent.OutboxEvent.ItemCompleted ->
                    if (event.driveId == driveId) loadAll()
                is BackendEvent.OutboxEvent.ItemFailed ->
                    if (event.driveId == driveId) loadAll()
                else -> {}
            }
        }
    }
}
