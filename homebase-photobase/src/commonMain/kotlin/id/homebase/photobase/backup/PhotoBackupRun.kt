package id.homebase.photobase.backup

import co.touchlab.kermit.Logger
import id.homebase.api.sync.database.DeviceBackupAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

enum class BackupRunOutcome { AlreadyRunning, Paused, Finished, OutOfTime }

/**
 * The one backup run every trigger calls. It never assumes which trigger that was, and missing a
 * trigger only costs latency: all it trusts is the ledger, the checkpoint and the server.
 */
class PhotoBackupRun(
    private val settings: () -> BackupSettings,
    private val ledger: BackupLedger,
    private val source: DeviceChangeSource,
    private val hasher: AssetHasher,
    private val dedupe: BackupDedupe,
    private val uploaderFor: (BackupRunRequest) -> BackupUploader,
    private val results: BackupOutboxResults,
    private val probe: BackupDeviceProbe,
    private val auth: BackupAuth,
    private val scheduler: BackupScheduler,
    private val isStorageFull: suspend (interactive: Boolean) -> Boolean,
) {
    private val mutex = Mutex()

    private val _status = MutableStateFlow(BackupStatus())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    private var activeJob: Job? = null

    /** The OS took the time back: stop now, leaving every row where a later run can pick it up. */
    fun cancelActive() {
        activeJob?.cancel(CancellationException("backup run expired"))
    }

    suspend fun refreshStatus() {
        val counts = ledger.counts()
        val failures = ledger.failures(MAX_FAILURES_SHOWN)
        val lastSuccess = ledger.lastSuccessMs()
        val access = probe.libraryAccess()
        _status.update {
            it.copy(
                pendingCount = counts.pending + counts.inOutbox,
                uploadingCount = counts.uploading,
                backedUpCount = counts.done,
                failedCount = counts.failed,
                failures = failures,
                lastSuccessMs = lastSuccess,
                libraryAccess = access,
            )
        }
    }

    suspend fun resetStatus() {
        _status.value = BackupStatus()
        refreshStatus()
    }

    suspend fun retryFailures() {
        ledger.requeueFailed()
        refreshStatus()
    }

    suspend fun run(request: BackupRunRequest): BackupRunOutcome {
        if (!mutex.tryLock()) {
            Logger.d(tag = TAG) { "${request.trigger}: a run is already going" }
            return BackupRunOutcome.AlreadyRunning
        }
        activeJob = currentCoroutineContext()[Job]
        _status.update { it.copy(isRunning = true) }
        return try {
            runLocked(request)
        } finally {
            activeJob = null
            _status.update { it.copy(isRunning = false) }
            mutex.unlock()
        }
    }

    private suspend fun runLocked(request: BackupRunRequest): BackupRunOutcome {
        val interactive = request.isInteractive
        Logger.i(tag = TAG) { "start trigger=${request.trigger} budget=${request.budget.remaining}" }

        if (!auth.awaitSignedIn()) return paused(BackupPauseReason.SignedOut)
        val current = settings()
        scheduler.rearm(current)
        if (!current.enabled) return paused(BackupPauseReason.BackupOff)
        if (probe.libraryAccess() == LibraryAccess.None) return paused(BackupPauseReason.NoLibraryAccess)
        val deviceState = probe.state()
        backupPauseReason(current, deviceState, interactive)?.let { return paused(it) }
        if (isStorageFull(interactive)) return paused(BackupPauseReason.StorageFull)
        _status.update { it.copy(pauseReason = null) }

        results.start()
        // Limited access can grow without the change counters saying so, and a new album scope brings in old assets.
        val fullScan = request.forceFullScan || probe.libraryAccess() == LibraryAccess.Limited
        val changes = source.changes(ledger.deviceCheckpoint(current.albumIds, fullScan), current.albumIds, request.findDeletions || fullScan)
        ledger.apply(changes, current.onlyNewFromMs)
        ledger.saveDeviceCheckpoint(changes.newCheckpoint, current.albumIds)

        if (interactive) ledger.requeueFailed()
        val uploader = uploaderFor(request)
        reconcileHandedOff(uploader)
        refreshStatus()

        val includeVideos = backupVideosAllowed(current, deviceState, interactive)
        val newestFirst = ledger.isInitialBackupDone()
        val finished = withTimeoutOrNull(request.budget.remaining) {
            pipeline(request, uploader, includeVideos, newestFirst)
            true
        } ?: false
        uploader.kick()
        val settled = awaitUploadsSettled(request.budget)

        val counts = ledger.counts()
        if (finished && settled && counts.pending == 0L) {
            ledger.markSucceeded()
            if (!newestFirst) ledger.markInitialBackupDone()
        }
        scheduler.rearm(settings())
        refreshStatus()
        val outcome = if (request.budget.isExpired) BackupRunOutcome.OutOfTime else BackupRunOutcome.Finished
        Logger.i(tag = TAG) { "end trigger=${request.trigger} outcome=$outcome counts=$counts" }
        return outcome
    }

    private suspend fun paused(reason: BackupPauseReason): BackupRunOutcome {
        Logger.i(tag = TAG) { "paused: $reason" }
        _status.update { it.copy(pauseReason = reason) }
        refreshStatus()
        return BackupRunOutcome.Paused
    }

    /** After a kill the ledger can say QUEUED for a row whose send already finished. Dedupe settles those. */
    private suspend fun reconcileHandedOff(uploader: BackupUploader) {
        for (row in ledger.rowsInOutbox()) {
            val uniqueId = row.uniqueId?.let(Uuid::parse)
            if (uniqueId == null || !uploader.isStillSending(row, uniqueId)) {
                ledger.onOutboxDropped(uniqueId ?: continue, "no send under way")
            }
        }
    }

    private suspend fun pipeline(
        request: BackupRunRequest,
        uploader: BackupUploader,
        includeVideos: Boolean,
        newestFirst: Boolean,
    ) = coroutineScope {
        val hashing = launch { hashStage(request, includeVideos, newestFirst) }
        val toPrepare = Channel<Pair<DeviceBackupAsset, Uuid>>()
        launch {
            try {
                dedupeStage(request, includeVideos, newestFirst, hashing, toPrepare)
            } finally {
                toPrepare.close()
            }
        }
        repeat(PREPARE_PARALLELISM) {
            launch {
                for ((row, uniqueId) in toPrepare) {
                    if (request.budget.isExpired || !awaitUploadRoom(uploader) || isStorageFull(false)) {
                        toPrepare.cancel()
                        return@launch
                    }
                    prepare(row, uniqueId, uploader)
                }
            }
        }
    }

    private suspend fun hashStage(request: BackupRunRequest, includeVideos: Boolean, newestFirst: Boolean) {
        val claimed = mutableSetOf<String>()
        coroutineScope {
            val slots = Channel<DeviceBackupAsset>()
            repeat(HASH_PARALLELISM) {
                launch {
                    for (row in slots) hash(row)
                }
            }
            while (!request.budget.isExpired) {
                val batch = ledger.due(BackupState.New, includeVideos, HASH_BATCH + claimed.size, newestFirst)
                    .filter { it.deviceAssetId !in claimed }
                if (batch.isEmpty()) break
                for (row in batch) {
                    claimed += row.deviceAssetId
                    slots.send(row)
                }
            }
            slots.close()
        }
    }

    private suspend fun hash(row: DeviceBackupAsset) {
        guarded(row) {
            val hashed = hasher.hash(row.deviceAssetId, BackupMediaType.ofCode(row.mediaType))
            ledger.markHashed(row.deviceAssetId, hashed.contentHash, hashed.uniqueId)
        }
    }

    private suspend fun dedupeStage(
        request: BackupRunRequest,
        includeVideos: Boolean,
        newestFirst: Boolean,
        hashing: Job,
        toPrepare: Channel<Pair<DeviceBackupAsset, Uuid>>,
    ) {
        val checked = mutableSetOf<String>()
        while (!request.budget.isExpired) {
            // Read before the query: a hash landing just after it must not look like the last word.
            val hashingDone = hashing.isCompleted
            val seen = ledger.changes.value
            val batch = ledger.due(BackupState.Hashed, includeVideos, DEDUPE_BATCH + checked.size, newestFirst)
                .filter { it.deviceAssetId !in checked }
            if (batch.isEmpty()) {
                if (hashingDone) return
                coroutineScope {
                    val waiter = launch { ledger.changes.first { it != seen } }
                    select { waiter.onJoin {}; hashing.onJoin {} }
                    waiter.cancel()
                }
                continue
            }
            checked += batch.map { it.deviceAssetId }
            val ids = batch.associateWith { Uuid.parse(it.uniqueId!!) }
            val existing = try {
                dedupe.existing(ids.values.toList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not knowing means not uploading: a failed check can't prove the photo isn't there.
                Logger.w(tag = TAG, throwable = e) { "dedupe check failed" }
                batch.forEach { ledger.recordFailure(it, e.message ?: "dedupe check failed") }
                continue
            }
            for ((row, uniqueId) in ids) {
                if (uniqueId in existing) ledger.markDone(row.deviceAssetId) else toPrepare.send(row to uniqueId)
            }
        }
    }

    private suspend fun prepare(row: DeviceBackupAsset, uniqueId: Uuid, uploader: BackupUploader) {
        guarded(row) {
            when (val handedOff = uploader.handOff(row, uniqueId)) {
                HandOffResult.Queued -> {
                    ledger.markQueued(row.deviceAssetId)
                    results.onHandedOff(uniqueId)
                    uploader.kick()
                }
                is HandOffResult.Sending -> ledger.markSending(uniqueId, handedOff.taskId)
                HandOffResult.ChangedUnderUs -> ledger.markChangedUnderUs(row.deviceAssetId)
            }
        }
    }

    private suspend inline fun guarded(row: DeviceBackupAsset, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssetPermanentlyUnavailableException) {
            ledger.markSkipped(row.deviceAssetId, e.reason)
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "asset ${row.deviceAssetId} failed in state ${row.state}" }
            ledger.recordFailure(row, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /** Small batches, so a whole library is never encrypted up front. False: nothing is moving, so stop until a later run. */
    private suspend fun awaitUploadRoom(uploader: BackupUploader): Boolean {
        while (true) {
            val counts = ledger.counts()
            if (counts.inOutbox + counts.uploading < uploader.maxHandedOff) return true
            if (uploader.outlivesProcess || results.unsettled.value.isEmpty()) return false
            if (!awaitActivity()) return false
        }
    }

    private suspend fun awaitUploadsSettled(budget: BackupBudget): Boolean {
        if (budget.isExpired) return results.unsettled.value.isEmpty()
        return withTimeoutOrNull(budget.remaining) {
            while (results.unsettled.value.isNotEmpty()) {
                if (!awaitActivity()) return@withTimeoutOrNull false
            }
            true
        } ?: false
    }

    private suspend fun awaitActivity(): Boolean = withTimeoutOrNull(NO_PROGRESS_LIMIT) {
        combine(results.activity, results.unsettled) { _, _ -> Unit }.drop(1).first()
        true
    } ?: false

    companion object {
        private const val TAG = "PhotoBackupRun"
        private const val HASH_PARALLELISM = 3
        private const val HASH_BATCH = 16
        private const val DEDUPE_BATCH = 20
        private const val PREPARE_PARALLELISM = 2
        private const val MAX_FAILURES_SHOWN = 20
        private val NO_PROGRESS_LIMIT: Duration = 2.minutes

        val WAKE_BUDGET: Duration = 25.seconds
        val WORKER_BUDGET: Duration = 9.minutes
    }
}
