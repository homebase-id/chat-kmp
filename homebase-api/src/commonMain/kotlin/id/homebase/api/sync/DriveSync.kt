package id.homebase.api.sync

import co.touchlab.kermit.Logger
import id.homebase.api.sync.database.CursorStorage
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.FileState
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResponse
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import kotlin.time.measureTimedValue
import kotlinx.coroutines.sync.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.collections.mutableListOf


class DriveSync(
    private val identityId: Uuid,
    private val driveId: Uuid,
    private val driveQueryProvider: DriveQueryProvider, // TODO: <- can we get rid of this?
    private val databaseManager: DatabaseManager,
    private val eventBus: EventBus,
    scope: CoroutineScope? = null
) {
    // Background work is Network and DB bound, so using IO
    private val scope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cursor: QueryBatchCursor?
    private val mutex = Mutex()
    private var batchSize = 500 // Balanced starting point
    private var fileHeaderProcessor = MainIndexMetaHelpers.HomebaseFileProcessor(databaseManager)
    private var job: Job? = null
    // Create companion object that prevents the creation of duplicate drives
    companion object {
        val drives = mutableListOf<Uuid>()
    }

    //TODO: Consider having a (readable) "last modified" which holds the largest timestamp of last-modified

    init {
        if (drives.contains(driveId)) {
            throw IllegalStateException("Another instance with the same driveId is already connected.")
        }
        drives.add(driveId);
        //XXX DETECT but battery!!!
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
        cursorStorage.deleteCursor();
        cursor = null
    }

    fun isJobRunning(): Boolean {
        return job != null
    }

    fun cancel() {
        // If we really really want to cancel in the future... Something like:
        // job?.cancel()?
        // we probably want child jobs to be allowed to complete (write to DB)
    }

    // sync() spawn a thread unless it's already working. Returns a pointer to the
    // Job created, or null if another job was already running. You can check if a
    // job is running by calling isJobRunning()
    fun sync(): Job? {
        if (!mutex.tryLock()) {
            return null
        }
        job = scope.launch {
            try {
                performSync()
            } finally {
                job = null
                mutex.unlock()
            }
        }

        return job
    }

    private suspend fun performSync() {
        var totalCount = 0
        var queryBatchResponse: QueryBatchResponse? = null
        var keepGoing = true

        eventBus.emit(BackendEvent.DriveEvent.Started(driveId))

        while (keepGoing) {
            Logger.i("Querying host for ${batchSize} rows")
            val request = QueryBatchRequest(
                queryParams = FileQueryParams(
                    fileState = listOf(FileState.Active) // <-- TODO: We want them all, not just "active"?
                ),
                resultOptionsRequest = QueryBatchResultOptionsRequest(
                    maxRecords = batchSize,
                    includeMetadataHeader = true,
                    cursorState = cursor?.toJson()
                )
            )

            var recordsRead = 0
            val durationMs = measureTimedValue {
                try {
                    queryBatchResponse = driveQueryProvider.queryBatch(driveId, request)

                    if (queryBatchResponse.cursorState != null)
                        cursor = QueryBatchCursor.fromJson(queryBatchResponse.cursorState)

                    val searchResults = queryBatchResponse.searchResults
                    if (searchResults.isNotEmpty()) {
                        recordsRead = searchResults.size
                        totalCount += recordsRead

                        // Run DB operation in background without waiting - fire and forget
                        scope.launch {
                            try {
                                val dbMs = measureTimedValue {
                                    fileHeaderProcessor.baseUpsertEntryZapZap(
                                        identityId = identityId,
                                        driveId = driveId,
                                        fileHeaders = searchResults,
                                        cursor = cursor
                                    )
                                }
                                // Logger.i("DB insert time $dbMs for ${searchResults.size} rows")
                            } catch (e: Exception) {
                                Logger.e("DB upsert failed for batch: ${e.message}")
                            }
                        }

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

                    keepGoing = queryBatchResponse.hasMoreRows

                } catch (e: Exception) {
                    eventBus.emit(
                        BackendEvent.DriveEvent.Failed(
                            driveId,
                            "Sync failed: ${e.message}"
                        )
                    )
                    keepGoing = false
                }
            }

            if (recordsRead > 0) {
                val batchWas = batchSize
                if (durationMs.duration.inWholeMilliseconds > 2000)
                    batchSize = ((batchSize * 3) / 4).coerceIn(50, 1000)
                else
                    batchSize = (batchSize * 2).coerceIn(50,1000)

                Logger.d("Batch size: $batchWas, took ${durationMs.duration.inWholeMilliseconds}ms, now adjusted to: $batchSize")
            }
        }

        eventBus.emit(BackendEvent.DriveEvent.Completed(driveId, totalCount))
    }
}