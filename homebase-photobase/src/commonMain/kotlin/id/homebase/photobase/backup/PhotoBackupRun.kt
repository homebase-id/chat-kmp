package id.homebase.photobase.backup

import id.homebase.api.video.BackgroundVideoWork
import id.homebase.api.video.VideoWorkYieldedException
import co.touchlab.kermit.Logger
import id.homebase.api.client.ServerException
import id.homebase.api.client.isTransientNetworkFailure
import id.homebase.api.sync.database.DeviceBackupAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

enum class BackupRunOutcome {
    /** Another run is going and this trigger doesn't outrank it. */
    AlreadyRunning,

    /** "Back up now" took over before this run finished. */
    Superseded,
    Paused,
    Finished,
    OutOfTime,
}

/**
 * The one backup run every trigger calls. It never assumes which trigger that was, and missing a
 * trigger only costs latency: all it trusts is the ledger, the checkpoint and the server.
 *
 * Only one runs at a time. A trigger meeting a run that's going:
 * - "Back up now" stops it and runs instead, so the user's run is the one left going when the app closes;
 * - opening the app waits for a background or user run, then runs after it;
 * - every other trigger does nothing, since the run going covers it.
 */
class PhotoBackupRun(
    private val settings: () -> BackupSettings,
    private val ledger: BackupLedger,
    private val source: DeviceChangeSource,
    private val hasher: AssetHasher,
    private val dedupe: BackupDedupe,
    private val uploader: BackupUploader,
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

    // Guards activeJob's setting and the four fields below, which callers outside the run's lock read and change.
    private val admission = Mutex()
    private var stopping = 0
    private var activeTrigger: BackupTrigger? = null
    private var activeWork: Job? = null
    private var userRequestsWaiting = 0

    /** The OS took the time back: stop now, leaving every row where a later run can pick it up. */
    fun cancelActive() {
        activeJob?.cancel(CancellationException("backup run expired"))
    }

    /** Returns once the run that was going has written its last row, so a wipe after this can't be written over. */
    suspend fun stop() = exclusively {}

    /** Runs already waiting for the lock give way rather than start, so they can't get in ahead of [block]. */
    private suspend fun <T> exclusively(block: suspend () -> T): T {
        admission.withLock {
            stopping++
            activeJob?.cancel(CancellationException("backup stopped"))
        }
        try {
            return mutex.withLock { block() }
        } finally {
            withContext(NonCancellable) { admission.withLock { stopping-- } }
        }
    }

    /** Keeps the counts live for whoever is watching, including results that land with no run going. At most one refresh a second: a run ticks per row. */
    suspend fun followLedger() {
        ledger.changes.collect {
            if (_status.subscriptionCount.value > 0) refreshStatus()
            delay(STATUS_REFRESH_GAP)
        }
    }

    suspend fun albums(): List<DeviceAlbum> = source.albums()

    suspend fun takeOtherPushWake(gap: Duration): Boolean = ledger.takeOtherPushWake(gap)

    suspend fun markWokenInBackground() = ledger.markWokenInBackground()

    suspend fun takeWokenInBackground(): Boolean = ledger.takeWokenInBackground()

    suspend fun hasUnsentWork(): Boolean = ledger.counts().unsent > 0

    suspend fun refreshStatus() {
        val counts = ledger.counts()
        val failures = ledger.failures(MAX_FAILURES_SHOWN)
        val lastSuccess = ledger.lastSuccessMs()
        val access = probe.libraryAccess()
        val refreshOff = settings().enabled && probe.isBackgroundRefreshOff()
        _status.update {
            it.copy(
                pendingCount = counts.pending + counts.inOutbox,
                uploadingCount = counts.uploading,
                backedUpCount = counts.done,
                backedUpBytes = counts.doneBytes,
                remainingBytes = counts.remainingBytes,
                failedCount = counts.failed,
                failures = failures,
                lastSuccessMs = lastSuccess,
                libraryAccess = access,
                photoLocationsWithheld = access != LibraryAccess.None && probe.photoLocationsWithheld(),
                backgroundRefreshOff = refreshOff,
            )
        }
    }

    suspend fun resetStatus() {
        _status.value = BackupStatus()
        refreshStatus()
    }

    /** Under the run's lock, so a run that was going can't write a row back after the wipe. */
    suspend fun forget() {
        exclusively { ledger.clear() }
        resetStatus()
    }

    suspend fun retryFailures() {
        ledger.requeueFailed()
        refreshStatus()
    }

    suspend fun recheckDrive() {
        ledger.recheckDone()
        refreshStatus()
    }

    suspend fun run(request: BackupRunRequest): BackupRunOutcome {
        if (!admit(request.trigger)) {
            Logger.d(tag = TAG) { "${request.trigger}: a run is already going" }
            return BackupRunOutcome.AlreadyRunning
        }
        val job = currentCoroutineContext()[Job]
        // Holding the run's lock now: a cancel here must not leave it held.
        val givesWay = withContext(NonCancellable) { admission.withLock { (stopping > 0).also { if (!it) activeJob = job } } }
        if (givesWay) {
            mutex.unlock()
            Logger.d(tag = TAG) { "${request.trigger}: backup is stopping" }
            return BackupRunOutcome.AlreadyRunning
        }
        _status.update { it.copy(isRunning = true) }
        val timings = BackupTimings()
        val started = TimeSource.Monotonic.markNow()
        return try {
            val context = if (request.mayDownloadFromCloud) timings else timings + NoCloudDownloads
            val outcome = try {
                coroutineScope {
                    val work = async(context) { followingTheNetwork { runLocked(request) } }
                    admission.withLock {
                        activeTrigger = request.trigger
                        activeWork = work
                        // A "Back up now" that arrived while this one waited for the lock.
                        if (userRequestsWaiting > 0) work.cancel(Superseded())
                    }
                    work.await()
                }
            } catch (e: Superseded) {
                currentCoroutineContext().ensureActive()
                Logger.i(tag = TAG) { "${request.trigger}: \"Back up now\" took over" }
                BackupRunOutcome.Superseded
            }
            if (outcome != BackupRunOutcome.Superseded) noteForStuckAlert(request, outcome)
            outcome
        } finally {
            withContext(NonCancellable) {
                admission.withLock {
                    activeTrigger = null
                    activeWork = null
                }
            }
            hasher.forgetAll()
            Logger.i(tag = TAG) { "timing trigger=${request.trigger} total=${started.elapsedNow().inWholeMilliseconds}ms ${timings.summary()}" }
            activeJob = null
            _status.update { it.copy(isRunning = false) }
            mutex.unlock()
        }
    }

    /** True once this trigger holds the lock; false when it gives way to the run that's going. */
    private suspend fun admit(trigger: BackupTrigger): Boolean {
        if (mutex.tryLock()) return true
        val userRequested = trigger == BackupTrigger.UserRequested
        val waits = admission.withLock {
            when {
                userRequested -> {
                    userRequestsWaiting++
                    activeWork?.cancel(Superseded())
                    true
                }
                trigger == BackupTrigger.Foreground -> activeTrigger != BackupTrigger.Foreground
                else -> false
            }
        }
        if (!waits) return false
        try {
            mutex.lock()
        } finally {
            if (userRequested) withContext(NonCancellable) { admission.withLock { userRequestsWaiting-- } }
        }
        return true
    }

    private class Superseded : CancellationException("\"Back up now\" took over")

    private suspend fun noteForStuckAlert(request: BackupRunRequest, outcome: BackupRunOutcome) {
        if (outcome != BackupRunOutcome.Paused) {
            ledger.markRan()
            scheduler.onBackupRan(workLeft = ledger.counts().workLeft)
            return
        }
        val reason = _status.value.pauseReason
        if (request.isInteractive || reason == BackupPauseReason.BackupOff || reason == BackupPauseReason.SignedOut) return
        if (ledger.counts().pending > 0 && ledger.takeStuckAlert()) scheduler.alertBackupStuck()
    }

    /**
     * The network settings, free space and heat hold for the whole run, not just its start. Turning backup off, losing
     * Wi-Fi under Wi-Fi only, running low on space or getting too hot pauses it. Losing what videos need starts it over, which
     * leaves videos out and carries on with photos. A send already under way isn't recalled.
     */
    private suspend fun followingTheNetwork(run: suspend () -> BackupRunOutcome): BackupRunOutcome {
        while (true) {
            runUntilNetworkChange(run)?.let { return it }
        }
    }

    /** Null: run it again. */
    private suspend fun runUntilNetworkChange(run: suspend () -> BackupRunOutcome): BackupRunOutcome? = coroutineScope {
        val running = async { run() }
        val change = async { awaitNetworkChange() }
        select {
            running.onAwait { outcome ->
                change.cancel()
                outcome
            }
            change.onAwait { reason ->
                running.cancelAndJoin()
                reason?.let {
                    scheduler.rearm(settings())
                    paused(it)
                }
            }
        }
    }

    /** The reason to pause, or null when only what videos need was lost. */
    private suspend fun awaitNetworkChange(): BackupPauseReason? {
        val videosWereAllowed = networkAllowsVideos(settings(), probe.state())
        while (true) {
            delay(NETWORK_RECHECK)
            val current = settings()
            val state = probe.state()
            if (!current.enabled) return BackupPauseReason.BackupOff
            val reason = backupPauseReason(current, state, interactive = true)
            if (reason in PAUSES_MID_RUN) return reason
            if (videosWereAllowed && !networkAllowsVideos(current, state)) return null
        }
    }

    private suspend fun runLocked(request: BackupRunRequest): BackupRunOutcome = coroutineScope {
        val interactive = request.isInteractive
        Logger.i(tag = TAG) { "start trigger=${request.trigger} budget=${request.budget.remaining}" }

        // Finding and hashing photos are local, so a slow login restore mustn't hold them up in a short wake.
        val signedIn = async { timed(BackupStage.Login) { auth.awaitSignedIn() } }
        val current = settings()
        val deviceState = probe.state()
        val localPause = when {
            !current.enabled -> BackupPauseReason.BackupOff
            probe.libraryAccess() == LibraryAccess.None -> BackupPauseReason.NoLibraryAccess
            else -> backupPauseReason(current, deviceState, interactive)
                ?: BackupPauseReason.StorageFull.takeIf { isStorageFull(false) }
        }
        if (localPause != null) {
            if (!signedIn.await()) return@coroutineScope paused(BackupPauseReason.SignedOut)
            scheduler.rearm(current)
            return@coroutineScope paused(localPause)
        }

        // Limited access can grow without the change counters saying so, and a new album scope brings in old assets.
        val fullScan = request.forceFullScan || probe.libraryAccess() == LibraryAccess.Limited
        timed(BackupStage.Scan) {
            val changes = source.changes(ledger.deviceCheckpoint(current.albumIds, fullScan), current.albumIds, request.findDeletions || fullScan)
            ledger.apply(changes, current.onlyNewFromMs)
            ledger.saveDeviceCheckpoint(changes.newCheckpoint, current.albumIds)
        }
        if (interactive) ledger.requeueFailed()
        if (request.mayDownloadFromCloud) ledger.clearNotOnDeviceDeferrals()
        val before = ledger.counts()

        val includeVideos = backupVideosAllowed(current, deviceState, interactive)
        val initialBackupDone = ledger.isInitialBackupDone()
        val newestFirst = current.newestFirst || initialBackupDone
        val hashing = launch {
            withTimeoutOrNull(request.budget.remaining) { hashStage(request, includeVideos, newestFirst) }
        }

        if (!signedIn.await()) {
            hashing.cancelAndJoin()
            return@coroutineScope paused(BackupPauseReason.SignedOut)
        }
        scheduler.rearm(current)
        if (isStorageFull(interactive)) {
            hashing.cancelAndJoin()
            return@coroutineScope paused(BackupPauseReason.StorageFull)
        }
        _status.update { it.copy(pauseReason = null) }

        results.start()
        reconcileHandedOff()
        refreshStatus()

        val finished = withTimeoutOrNull(request.budget.remaining) {
            pipeline(request, includeVideos, newestFirst, hashing)
            true
        } ?: false
        uploader.kick()
        val settled = timed(BackupStage.Settle) { awaitUploadsSettled(request) }

        val counts = ledger.counts()
        if (finished && settled && counts.pending == 0L && counts.inOutbox + counts.uploading == 0L) {
            ledger.markSucceeded()
            if (!initialBackupDone) ledger.markInitialBackupDone()
        }
        scheduler.rearm(settings())
        // A write that failed on a full phone can be the run's last work, ending it before the watcher looks.
        backupPauseReason(settings(), probe.state(), interactive = true)?.takeIf { it in DEVICE_PAUSES }?.let {
            return@coroutineScope paused(it)
        }
        refreshStatus()
        val outcome = if (request.budget.isExpired) BackupRunOutcome.OutOfTime else BackupRunOutcome.Finished
        Logger.i(tag = TAG) { "end trigger=${request.trigger} outcome=$outcome counts=$counts" }
        // A run that moved nothing would only loop; it waits for the usual triggers.
        val madeProgress = counts.done > before.done || counts.pending < before.pending
        if (outcome == BackupRunOutcome.OutOfTime && counts.unsent > 0 && madeProgress) {
            Logger.i(tag = TAG) { "continuing: ${counts.unsent} left" }
            scheduler.continueSoon(settings())
        }
        outcome
    }

    private suspend fun paused(reason: BackupPauseReason): BackupRunOutcome {
        Logger.i(tag = TAG) { "paused: $reason" }
        _status.update { it.copy(pauseReason = reason) }
        refreshStatus()
        return BackupRunOutcome.Paused
    }

    /** After a kill the ledger can say QUEUED for a row whose send already finished. Dedupe settles those. */
    private suspend fun reconcileHandedOff() {
        val handedOff = ledger.rowsInOutbox().mapNotNull { row -> row.uniqueId?.let { row to Uuid.parse(it) } }.toMap()
        val sending = uploader.stillSending(handedOff)
        for (uniqueId in handedOff.values - sending) ledger.onOutboxDropped(uniqueId, "no send under way", countsAttempt = false)
    }

    private suspend fun pipeline(
        request: BackupRunRequest,
        includeVideos: Boolean,
        newestFirst: Boolean,
        hashing: Job,
    ) = coroutineScope {
        val toPrepare = Channel<Pair<DeviceBackupAsset, Uuid>>()
        launch {
            try {
                dedupeStage(request, includeVideos, newestFirst, hashing, toPrepare)
            } finally {
                toPrepare.close()
            }
        }
        repeat(PREPARE_PARALLELISM) {
            // On the worker, not withContext around the hand-off: that would throw away a result if cancelled on the way out.
            launch(BackgroundVideoWork) {
                for ((row, uniqueId) in toPrepare) {
                    if (request.budget.isExpired || !timed(BackupStage.WaitForRoom) { awaitUploadRoom(row, request.isInteractive) } || isStorageFull(false)) {
                        toPrepare.cancel()
                        return@launch
                    }
                    prepare(row, uniqueId)
                }
            }
        }
        hashing.join()
    }

    private suspend fun hashStage(request: BackupRunRequest, includeVideos: Boolean, newestFirst: Boolean) {
        val claimed = mutableSetOf<String>()
        var waitForExportRoom = true
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
                    if (waitForExportRoom && row.mediaType != BackupMediaType.Video.code) {
                        waitForExportRoom = hasher.awaitExportRoom(NO_PROGRESS_LIMIT)
                    }
                    slots.send(row)
                }
            }
            slots.close()
        }
    }

    private suspend fun hash(row: DeviceBackupAsset) {
        guarded(row) {
            val hashed = hasher.hash(row.deviceAssetId, BackupMediaType.ofCode(row.mediaType))
            row.contentHash?.let { previous ->
                Logger.i(tag = TAG) { "rehashed ${row.deviceAssetId} modifiedAt=${row.modifiedAt} unchanged=${previous == hashed.contentHash}" }
            }
            ledger.markHashed(row.deviceAssetId, hashed.contentHash, hashed.uniqueId, hashed.sizeBytes)
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
                timed(BackupStage.ServerCheck) { dedupe.existing(ids.values.toList()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not knowing means not uploading: a failed check can't prove the photo isn't there.
                Logger.w(tag = TAG, throwable = e) { "dedupe check failed" }
                val reason = e.message ?: "dedupe check failed"
                if (e.isOutage()) {
                    ledger.deferForOutage(batch, reason)
                    return
                }
                batch.forEach { ledger.recordFailure(it, reason) }
                continue
            }
            for ((row, uniqueId) in ids) {
                if (uniqueId in existing) {
                    ledger.markDone(row.deviceAssetId)
                    hasher.forget(row.deviceAssetId)
                } else {
                    toPrepare.send(row to uniqueId)
                }
            }
        }
    }

    private suspend fun prepare(row: DeviceBackupAsset, uniqueId: Uuid) {
        guarded(row) {
            val handedOff = timed(BackupStage.Prepare) { uploader.handOff(row, uniqueId) }
            // Leaving the app cancels the run. A row left unmarked is sent again, over a body file the session is still reading.
            withContext(NonCancellable) {
                when (handedOff) {
                    HandOffResult.Queued -> {
                        // Tracked first: a verdict landing between the two calls must find it to settle.
                        results.onHandedOff(uniqueId)
                        if (ledger.markQueued(row.deviceAssetId)) results.onSettledBeforeHandOff(uniqueId)
                    }
                    is HandOffResult.Sending -> ledger.markSending(uniqueId, handedOff.taskId)
                    HandOffResult.ChangedUnderUs -> ledger.markChangedUnderUs(row.deviceAssetId)
                }
            }
            if (handedOff == HandOffResult.Queued) uploader.kick()
        }
    }

    private suspend inline fun guarded(row: DeviceBackupAsset, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AssetPermanentlyUnavailableException) {
            ledger.markSkipped(row.deviceAssetId, e.reason)
        } catch (e: AssetNotOnDeviceException) {
            ledger.deferUntilOnDevice(row)
        } catch (e: VideoWorkYieldedException) {
            ledger.deferForOtherVideoWork(row)
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "asset ${row.deviceAssetId} failed in state ${row.state}" }
            // A full phone fails the write, not the photo; the watcher pauses the run.
            if (!probe.state().isPhoneStorageLow) ledger.recordFailure(row, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    /** Small batches, so a whole library is never encrypted up front. False: nothing is moving, so stop until a later run. */
    private suspend fun awaitUploadRoom(row: DeviceBackupAsset, interactive: Boolean): Boolean {
        // Videos stay on the Outbox whatever the uploader's own limit.
        val isVideo = BackupMediaType.ofCode(row.mediaType) == BackupMediaType.Video
        while (true) {
            val outboxHasRoom = !isVideo || results.unsettled.value.size < MAX_OUTBOX_HANDED_OFF
            // Rows the Outbox is backing off don't hold up the rest; twice the limit stops an outage staging the library.
            val hasRoom = ledger.sendingCount() < uploader.maxHandedOff && ledger.handedOffCount() < uploader.maxHandedOff * 2
            if (hasRoom && outboxHasRoom) return true
            val waitingIsUseful = if (uploader.outlivesProcess) interactive else results.unsettled.value.isNotEmpty()
            if (!waitingIsUseful || !awaitActivity()) return false
        }
    }

    private suspend fun awaitUploadsSettled(request: BackupRunRequest): Boolean {
        val budget = request.budget
        if (budget.isExpired) return results.unsettled.value.isEmpty()
        val waitForSends = request.isInteractive && uploader.outlivesProcess
        return withTimeoutOrNull(budget.remaining) {
            while (results.unsettled.value.isNotEmpty() || (waitForSends && ledger.counts().uploading > 0)) {
                if (!awaitActivity()) return@withTimeoutOrNull false
            }
            true
        } ?: false
    }

    private suspend fun awaitActivity(): Boolean = withTimeoutOrNull(NO_PROGRESS_LIMIT) {
        combine(results.progress, results.unsettled, ledger.changes) { _, _, _ -> Unit }.drop(1).first()
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
        private val STATUS_REFRESH_GAP: Duration = 1.seconds
        private val NETWORK_RECHECK: Duration = 5.seconds
        private val DEVICE_PAUSES = setOf(BackupPauseReason.PhoneStorageLow, BackupPauseReason.TooHot)
        private val PAUSES_MID_RUN = DEVICE_PAUSES + BackupPauseReason.WaitingForWifi

        val WORKER_BUDGET: Duration = 9.minutes
    }
}

private fun Exception.isOutage(): Boolean =
    isTransientNetworkFailure() || (this is ServerException && (status >= 500 || status == 408 || status == 429))
