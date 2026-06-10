package id.homebase.api.sync

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResponse
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.ForbiddenException
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import id.homebase.api.sync.database.CursorStorage
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.coroutines.supervisedScope
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import id.homebase.api.common.time.UnixTimeUtc
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
    private val policy: DriveSyncPolicy = DriveSyncPolicy(),
    // Owning identity when this drive is hosted on a peer (a community owner); null for the
    // logged-in user's own drives. When set, the sync pull is brokered over peer (queryBatch routes
    // to /peer/$ownerOdinId/...). Rows still land under the local-user [identityId] — the community
    // driveId is a globally-unique GUID so it can never collide with own-drive rows.
    private val ownerOdinId: OdinId? = null,
) {
    // Background work is Network and DB bound, so using IO
    private val scope = scope ?: supervisedScope("drive-sync")
    private var cursor: QueryBatchCursor?
    private val mutex = Mutex()
    private var batchSize = 500 // Balanced starting point
    private var fileHeaderProcessor = MainIndexMetaHelpers.HomebaseFileProcessor(databaseManager)
    private var job: Job? = null
    private val killroy = atomic(false)

    // True while a sync round is actively running (from acquiring the sync
    // lock until performSync's finally releases it). Distinct from
    // [isJobRunning], which can momentarily read false between a round's
    // finally and a killroy-triggered recursive re-sync. Consumers that
    // need "is the drive mid-sync right now" (e.g. deferring a write that
    // would race the sync's DB writes) should read this.
    private val syncing = atomic(false)

    // Epoch-ms when the last sync round finished (performSync's finally). 0 = no
    // round has completed this session. Consumers gate "is the drive quiet yet"
    // on this together with [syncing] (e.g. the lastRead flush waits a short
    // window after a round so the post-Stopped reload has reconciled).
    private val lastStoppedAt = atomic(0L)

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

    /** True while this drive is actively syncing (a round is in flight). */
    fun isSyncing(): Boolean = syncing.value

    /** Epoch-ms when the last sync round finished, or 0 if none has this session. */
    fun lastStoppedAtMs(): Long = lastStoppedAt.value

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
        syncing.value = true // Atomic — mirrors the sync-lock lifetime below
        job = scope.launch {
            try {
                performSync()
            } finally {
                job = null
                syncing.value = false
                lastStoppedAt.value = UnixTimeUtc().milliseconds
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

        // Snapshot fresh-sync state BEFORE the windowed loop below mutates `cursor`.
        // Fresh = first login or a discarded stale cursor (loadCursor returned null).
        // Both policy knobs gate on this; re-reading the field later would be wrong
        // because the first windowed batch overwrites `cursor`.
        val isFreshSync = cursor == null
        if (isFreshSync) {
            // (a) Custom initial queries (e.g. the chat conversation list). These
            //     persist NO cursor, so an interruption simply re-runs them on the
            //     next fresh sync. totalCount carries forward so the windowed
            //     Progress counts continue upward (DriveSyncManager's monotonic
            //     `>= current.count` guard would otherwise swallow the first one).
            totalCount = runInitialQueries(startCount = totalCount)

            // (b) Seed the windowed crawl floor. fromStartPoint sets paging.time
            //     only; OldestFirst makes the first page return rows changed after
            //     the floor. Subsequent pages advance via the response cursorState.
            policy.fullSyncWindow?.let { window ->
                val floor = UnixTimeUtc().addMilliseconds(-window.inWholeMilliseconds)
                cursor = QueryBatchCursor.fromStartPoint(floor)
                Logger.i("DriveSync: fresh sync on $driveId seeded window floor=${floor.milliseconds}")
            }
        }

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
                    queryBatchResponse = driveQueryProvider.queryBatch(driveId, request, ownerOdinId)

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
                                BackendEvent.DriveResult.Aborted("DB write failed: ${e.message ?: "unknown error"}")
                            )
                        )
                        return
                    }

                    if (searchResults.isNotEmpty()) {
                        recordsRead = searchResults.size
                        totalCount += recordsRead
                        val batchCursorToSave = cursor
                        val batchTotalCount = totalCount  // captured for the async closure

                        pendingDbJob = scope.async {
                            val (_, upsertElapsed) = measureTimedValue {
                                fileHeaderProcessor.baseUpsertEntryZapZap(
                                    identityId = identityId,
                                    driveId = driveId,
                                    fileHeaders = searchResults,
                                    cursor = batchCursorToSave
                                )
                            }
                            Logger.i {
                                "DriveSync: batch upsert drive=$driveId rows=${searchResults.size} took=$upsertElapsed"
                            }
                            // DriveSync is a silent batched DB write — no per-batch
                            // BatchReceived (consumers reload from DriveMainIndex on
                            // Stopped(Completed); live per-file events come via
                            // DriveWebSocketUpsertWorker WS push). The one signal we
                            // DO emit per batch is a payload-free Progress event so
                            // DriveSyncManager can advance Synchronizing(count) for
                            // the login-screen progress UI.
                            eventBus.emit(
                                BackendEvent.DriveEvent.Progress(driveId, batchTotalCount)
                            )
                        }
                    }

                    if (!queryBatchResponse.hasMoreRows)
                        break
                    retryCount = 0
                } catch (e: ForbiddenException) {
                    Logger.w("DriveSync: drive $driveId returned 403 Forbidden — unmounting for this session")
                    killroy.value = false
                    eventBus.emit(
                        BackendEvent.DriveEvent.Stopped(driveId, totalCount, BackendEvent.DriveResult.PermissionDenied)
                    )
                    break
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
                            BackendEvent.DriveResult.Aborted("Sync failed: $reason")
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
            if (totalCount > 0) {
                Logger.d("DriveSync: all DB writes complete for drive $driveId ($totalCount total records)")
            }
            eventBus.emit(BackendEvent.DriveEvent.Stopped(driveId, totalCount, BackendEvent.DriveResult.Completed))
            Logger.d("Drive $driveId synchronized with $totalCount records read.")
        } catch (e: Exception) {
            Logger.e("Sync failed due to DB error: ${e.message}")
            eventBus.emit(BackendEvent.DriveEvent.Stopped(driveId, totalCount, BackendEvent.DriveResult.Aborted(e.message ?: "DB upsert failed")))
        }
    }

    /**
     * Runs each [DriveSyncPolicy.initialQueries] entry to completion (full
     * pagination) at the start of a fresh sync, upserting results with
     * `cursor = null` so the drive's persisted CursorStorage position is never
     * touched. Returns the running total so the windowed crawl's Progress counts
     * continue from here. Uses the same OldestFirst / AnyChangeDate ordering as the
     * main crawl, paginating with a LOCAL cursor that's never saved.
     *
     * Best-effort: a query failure is logged and we fall through to the windowed
     * crawl rather than aborting the whole round — a conversation-list hiccup must
     * not block message sync. The next fresh sync re-runs these (idempotent
     * upserts), so nothing is lost.
     */
    private suspend fun runInitialQueries(startCount: Int): Int {
        if (policy.initialQueries.isEmpty()) return startCount
        var runningTotal = startCount

        for (queryParams in policy.initialQueries) {
            var initialCursor: QueryBatchCursor? = null
            while (true) {
                val request = QueryBatchRequest(
                    queryParams = queryParams,
                    resultOptionsRequest = QueryBatchResultOptionsRequest(
                        maxRecords = batchSize,
                        includeMetadataHeader = true,
                        cursorState = initialCursor?.toJson(),
                        includeTransferHistory = true,
                        ordering = QueryBatchSortOrder.OldestFirst,
                        sorting = QueryBatchSortField.AnyChangeDate
                    )
                )

                val response = try {
                    killroy.value = false // Atomic
                    driveQueryProvider.queryBatch(driveId, request)
                } catch (e: Exception) {
                    Logger.w("DriveSync: initial query on $driveId failed, continuing to windowed crawl: ${e.message}")
                    return runningTotal
                }

                if (response.cursorState != null)
                    initialCursor = QueryBatchCursor.fromJson(response.cursorState)

                val results = response.searchResults
                if (results.isNotEmpty()) {
                    // cursor = null: do NOT clobber the windowed drive cursor —
                    // performBaseUpsert persists any non-null cursor to CursorStorage.
                    fileHeaderProcessor.baseUpsertEntryZapZap(
                        identityId = identityId,
                        driveId = driveId,
                        fileHeaders = results,
                        cursor = null
                    )
                    runningTotal += results.size
                    eventBus.emit(BackendEvent.DriveEvent.Progress(driveId, runningTotal))
                }

                if (!response.hasMoreRows) break
            }
        }
        Logger.i("DriveSync: initial queries done on $driveId, $runningTotal records")
        return runningTotal
    }
}
