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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
    scope: CoroutineScope? = null,
    expectFreshCursor: Boolean = false,
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
        val cursorStorage = CursorStorage(databaseManager, driveId)
        cursor = cursorStorage.loadCursor(expectFresh = expectFreshCursor)
    }


    // Reset in-memory sync state on logout. Every SQL table this drive touches is
    // wiped centrally by DatabaseManager.wipeAndRecreate(), so this method only has
    // to zero the cursor we hold in memory — without this the next session would
    // resume from a stale QueryBatchCursor that no longer matches on-disk rows.
    fun resetInMemoryState() {
        cursor = null
    }

    fun isJobRunning(): Boolean {
        return job != null
    }

    fun cancel() {
        killroy.value = false
        job?.cancel()
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
            if (killroy.value) {
                Logger.i("DriveSync: killroy triggered recursive sync for drive $driveId")
                sync()
            }
        }

        return job
    }

    private suspend fun performSync() {
        var totalCount = 0
        var queryBatchResponse: QueryBatchResponse? = null
        var pendingDbJob: Deferred<Unit>? = null

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
                    // Gate: if previous batch's DB write failed, stop sync immediately
                    try {
                        pendingDbJob?.await()
                        pendingDbJob = null
                    } catch (e: Exception) {
                        Logger.e("DriveSync: DB write failed for drive $driveId, stopping sync: ${e.message}")
                        eventBus.emit(
                            BackendEvent.DriveEvent.Stopped(
                                driveId, totalCount,
                                BackendEvent.DriveResult.Failure("DB write failed: ${e.message ?: "unknown error"}")
                            )
                        )
                        return
                    }

                    if (searchResults.isNotEmpty()) {
                        recordsRead = searchResults.size
                        totalCount += recordsRead
                        val batchCursorToSave = cursor
                        val batchTotalCount = totalCount
                        val batchRecordsRead = recordsRead
                        val latestModified = searchResults.last().fileMetadata.updated

                        pendingDbJob = scope.async {
                            fileHeaderProcessor.baseUpsertEntryZapZap(
                                identityId = identityId,
                                driveId = driveId,
                                fileHeaders = searchResults,
                                cursor = batchCursorToSave
                            )

                            eventBus.emit(
                                BackendEvent.DriveEvent.BatchReceived(
                                    driveId = driveId,
                                    totalCount = batchTotalCount,
                                    batchCount = batchRecordsRead,
                                    latestModified = latestModified,
                                    batchData = searchResults
                                )
                            )
                        }
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

        try {
            pendingDbJob?.await()
            Logger.d("DriveSync: all DB writes complete for drive $driveId ($totalCount total records)")
            eventBus.emit(BackendEvent.DriveEvent.Stopped(driveId, totalCount, BackendEvent.DriveResult.Success))
            Logger.d("Drive $driveId synchronized with $totalCount records read.")
        } catch (e: Exception) {
            Logger.e("Sync failed due to DB error: ${e.message}")
            eventBus.emit(BackendEvent.DriveEvent.Stopped(driveId, totalCount, BackendEvent.DriveResult.Failure(e.message ?: "DB upsert failed")))
        }
    }
}