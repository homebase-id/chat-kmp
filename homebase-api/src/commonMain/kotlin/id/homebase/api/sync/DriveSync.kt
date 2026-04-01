package id.homebase.api.sync

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResponse
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.sync.database.CursorStorage
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlin.time.measureTimedValue
import kotlin.uuid.Uuid

class DriveSync(
    private val identityId: Uuid,
    private val driveId: Uuid,
    private val driveQueryProvider: DriveQueryProvider, // TODO: <- can we get rid of this?
    private val databaseManager: DatabaseManager,
    private val eventBus: EventBus,
    scope: CoroutineScope? = null
) {
    // Background work is Network and DB bound, so using IO
    private val scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var cursor: QueryBatchCursor?
    private val mutex = Mutex()
    private var batchSize = 500 // Balanced starting point
    private var fileHeaderProcessor = MainIndexMetaHelpers.HomebaseFileProcessor(databaseManager)
    private var job: Job? = null
    private val killroy = atomic(false)

    //TODO: Consider having a (readable) "last modified" which holds the largest timestamp of last-modified

    init {
        // Load cursor from database
        val cursorStorage = CursorStorage(databaseManager, driveId)
        cursor = cursorStorage.loadCursor()
    }


    // Call this to clear everything if you want to run a test and re-sync
    suspend fun clearStorage() {
        // Temp hack, remove soon.
        databaseManager.driveMainIndex.deleteAll() // TODO: <-- don't delete all! :-)
        databaseManager.driveTagIndex.deleteAll() // TODO: <-- don't delete all! :-)
        databaseManager.driveLocalTagIndex.deleteAll() // TODO: <-- don't delete all! :-)
        databaseManager.keyValue.deleteByKey(driveId) // TODO: <-- don't delete the cursor
        val cursorStorage = CursorStorage(databaseManager, driveId)
        cursorStorage.deleteCursor()
        cursor = null
    }

    fun isJobRunning(): Boolean {
        return job != null
    }

    fun cancel() {
        // If we really want to cancel in the future... Something like:
        // job?.cancel()?
        // we probably want child jobs to be allowed to complete (write to DB)
    }

    // sync() spawn a thread unless it's already working. Returns a pointer to the
    // Job created, or null if another job was already running. You can check if a
    // job is running by calling isJobRunning()
    fun sync(): Job? {
        if (!mutex.tryLock()) {
            killroy.value = true // Atomic
            return null
        }
        job = scope.launch {
            try {
                performSync()
            } finally {
                job = null
                mutex.unlock()
            }
            if (killroy.value)
                sync() // If killroy was here, do it one extra time
        }

        return job
    }

    private suspend fun performSync() {
        var totalCount = 0
        var queryBatchResponse: QueryBatchResponse? = null

        eventBus.emit(BackendEvent.DriveEvent.Started(driveId))

        var retryCount = 0
        val maxRetries = 3

        while (true) {
            Logger.i("Synchronizing drive $driveId")
            val request = QueryBatchRequest(
                queryParams = FileQueryParams(
                    // we want deleted too since we resync when the socket gets a file deleted event
                ),
                resultOptionsRequest = QueryBatchResultOptionsRequest(
                    maxRecords = batchSize,
                    includeMetadataHeader = true,
                    cursorState = cursor?.toJson(),
                    includeTransferHistory = true,
                    ordering = QueryBatchSortOrder.OldestFirst,
                    sorting = QueryBatchSortField.AnyChangeDate
                )
            )

            var recordsRead = 0
            val durationMs = measureTimedValue {
                try {
                    killroy.value = false // Atomic
                    queryBatchResponse = driveQueryProvider.queryBatch(driveId, request)

                    if (queryBatchResponse.cursorState != null)
                        cursor = QueryBatchCursor.fromJson(queryBatchResponse.cursorState)
                    Logger.i("Received ${queryBatchResponse.searchResults.size} records from QueryBatch() on Drive $driveId")

                    val searchResults = queryBatchResponse.searchResults
                    if (searchResults.any { it.fileMetadata.appData.fileType == 7878 }) {
                        val chatGroupIds = searchResults
                            .filter { it.fileMetadata.appData.fileType == 7878 }
                            .mapNotNull { it.fileMetadata.appData.groupId }
                            .distinct()
                        Logger.d("DriveSync: batch contains ${chatGroupIds.size} chat conversation(s): $chatGroupIds")
                    }
                    if (searchResults.isNotEmpty()) {
                        recordsRead = searchResults.size
                        totalCount += recordsRead
                        val batchCursorToSave = cursor

                        // Write to DB before emitting event to ensure DB is consistent
                        // when any event handler (e.g. loadConversation) reads from it.
                        fileHeaderProcessor.baseUpsertEntryZapZap(
                            identityId = identityId,
                            driveId = driveId,
                            fileHeaders = searchResults,
                            cursor = batchCursorToSave
                        )

                        val latestModified = searchResults.last().fileMetadata.updated

                        eventBus.emit(
                            BackendEvent.DriveEvent.BatchReceived(
                                driveId = driveId,
                                totalCount = totalCount,
                                batchCount = recordsRead,
                                latestModified = latestModified,
                                batchData = searchResults
                            )
                        )
                    }

                    if (!queryBatchResponse.hasMoreRows)
                        break
                    retryCount = 0
                } catch (e: Exception) {
                    val isTransientNetworkError = e::class.simpleName == "SocketException" ||
                        e.message?.contains("Software caused connection abort") == true ||
                        e.message?.contains("Connection reset") == true
                    if (isTransientNetworkError && retryCount < maxRetries) {
                        retryCount++
                        Logger.w("Network abort on drive $driveId, retrying (attempt $retryCount/$maxRetries): ${e.message}")
                        delay(1000L * retryCount)
                        continue
                    }
                    val cursorInfo = if (cursor != null) "mid-sync" else "fresh sync (cursor=null)"
                    val reason = if (isTransientNetworkError)
                        "Network error after $maxRetries retries: ${e.message}"
                    else
                        "Non-transient error (${e::class.simpleName}): ${e.message}"
                    Logger.e("Drive $driveId sync failed ($cursorInfo): $reason")
                    killroy.value = false // don't retry on terminal failure; reconnect will re-sync
                    eventBus.emit(
                        BackendEvent.DriveEvent.Stopped(
                            driveId,
                            totalCount,
                            BackendEvent.DriveResult.Failure("Sync failed: $reason")
                        )
                    )
                    break
                }
            }

            if (recordsRead > 0) {
                val batchWas = batchSize
                if (durationMs.duration.inWholeMilliseconds > 2000)
                    batchSize = ((batchSize * 3) / 4).coerceIn(50, 1000)
                else
                    batchSize = (batchSize * 2).coerceIn(50, 1000)

                Logger.d("Batch size: $batchWas, took ${durationMs.duration.inWholeMilliseconds}ms, now adjusted to: $batchSize")
            }
        }

        eventBus.emit(BackendEvent.DriveEvent.Stopped(driveId, totalCount, BackendEvent.DriveResult.Success))
        Logger.d("Drive $driveId synchronized with $totalCount records read.")
    }
}