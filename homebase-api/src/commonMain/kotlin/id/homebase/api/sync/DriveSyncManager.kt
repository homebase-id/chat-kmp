package id.homebase.api.sync

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

class DriveSyncManager(
    private val driveQueryProvider: DriveQueryProvider,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val databaseManager: DatabaseManager,
    // Mandatory drives this app always syncs (chat, contacts, ...). The list is an
    // invariant of the sync engine — encoded in the constructor rather than passed at
    // each start() so that no caller can forget them. Mounting happens via
    // [ensureMandatoryMounted], which the auth flow calls right after credentials become
    // valid (before the WebSocket handshake), so these drives appear in [driveStatuses]
    // independently of WS state.
    private val mandatoryDrives: Map<Uuid, String>,
) {
    // Immutable map reference — always replaced, never mutated in-place, preventing CME.
    // All writes are serialized via driveSyncsMutex, which provides the happens-before
    // guarantee needed for non-mutex readers (syncDrive, pause, clearStorage).
    private var driveSyncs: Map<Uuid, DriveSync> = emptyMap()
    private val driveSyncsMutex = Mutex()
    @kotlin.concurrent.Volatile private var _isRunning = false

    /**
     * Whether [start] has flipped the manager into the running state. Read-only for
     * external callers (the snapshot logging in [AuthConnectionCoordinator] uses this).
     */
    val isRunning: Boolean get() = _isRunning

    // Set by clearStorage(), consumed by the next start(). Lets a freshly constructed
    // DriveSync detect and loudly complain if a cursor survived logout.
    @kotlin.concurrent.Volatile private var expectFreshCursors = false

    private val _driveStatuses = MutableStateFlow<Map<Uuid, DriveStatus>>(emptyMap())
    val driveStatuses: StateFlow<Map<Uuid, DriveStatus>> = _driveStatuses.asStateFlow()

    val syncState: StateFlow<SyncState> = _driveStatuses
        .map { computeSyncState(it) }
        .stateIn(scope, SharingStarted.Eagerly, SyncState.Idle)

    fun numberOfDrivesSyncing(): Int =
        _driveStatuses.value.values.count { it.state is DriveState.Synchronizing }

    init {
        scope.launch {
            var previous: SyncState = SyncState.Idle
            syncState.collect { current ->
                if (previous::class != current::class) {
                    Logger.i(tag = "DriveSync") {
                        "syncState ${previous::class.simpleName} -> ${current::class.simpleName}"
                    }
                }
                when {
                    previous !is SyncState.Syncing && current is SyncState.Syncing ->
                        eventBus.emit(BackendEvent.SyncAllStarted)
                    previous is SyncState.Syncing && current is SyncState.Completed ->
                        eventBus.emit(BackendEvent.SyncAllStopped(BackendEvent.SyncAllResult.Success))
                    previous is SyncState.Syncing && current is SyncState.Failed ->
                        eventBus.emit(BackendEvent.SyncAllStopped(BackendEvent.SyncAllResult.Failure))
                }
                previous = current
            }
        }

        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is BackendEvent.DriveEvent.Started       -> updateState(event.driveId) {
                        it.copy(state = DriveState.Synchronizing())
                    }
                    is BackendEvent.DriveEvent.Progress -> {
                        // Only advance during an active sync (Started already fired).
                        // A stray Progress shouldn't re-animate a Completed/Failed drive,
                        // and we never want the count to go backwards mid-sync.
                        val current = _driveStatuses.value[event.driveId]?.state
                        if (current is DriveState.Synchronizing && event.totalCount >= current.count) {
                            Logger.d(tag = "DriveSync") {
                                "Progress drive=${event.driveId} count=${event.totalCount}"
                            }
                            updateState(event.driveId) {
                                it.copy(state = DriveState.Synchronizing(count = event.totalCount))
                            }
                        }
                    }
                    is BackendEvent.DriveEvent.Stopped -> when (val r = event.result) {
                        is BackendEvent.DriveResult.Completed -> updateState(event.driveId) {
                            it.copy(state = DriveState.Completed(totalCount = event.totalCount))
                        }
                        is BackendEvent.DriveResult.Aborted -> {
                            updateState(event.driveId) {
                                it.copy(state = DriveState.Failed(r.errorMessage))
                            }
                            Logger.w(tag = "DriveSync") {
                                "drive ${event.driveId} failed: ${r.errorMessage}, scheduling retry in 1s"
                            }
                            scope.launch {
                                delay(1000L)
                                Logger.i(tag = "DriveSync") { "retrying drive ${event.driveId}" }
                                driveSyncsMutex.withLock { driveSyncs[event.driveId] }?.sync()
                            }
                        }
                        is BackendEvent.DriveResult.PermissionDenied -> {
                            Logger.w(tag = "DriveSync") {
                                "drive ${event.driveId} denied (403) — unmounting for this session"
                            }
                            scope.launch { unmountDrive(event.driveId) }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun updateState(driveId: Uuid, transform: (DriveStatus) -> DriveStatus) {
        _driveStatuses.update { current ->
            val existing = current[driveId] ?: return@update current
            current + (driveId to transform(existing))
        }
    }

    /**
     * Register the mandatory drives ([mandatoryDrives]) into [driveSyncs] so they
     * surface in [driveStatuses] as [DriveState.Initialized]. Safe to call before
     * [start]; the network kick is deferred until [start] flips [_isRunning] true
     * and the caller invokes [syncAll]. Idempotent — repeat invocations are no-ops
     * via [mountDrive]'s alreadyExists guard.
     *
     * Called by [AuthConnectionCoordinator] right after credentials become valid
     * (before [connect]). This guarantees the mandatory drives are present
     * regardless of whether the WebSocket handshake ever completes.
     */
    suspend fun ensureMandatoryMounted() {
        Logger.i(tag = "DriveSync") {
            "ensureMandatoryMounted begin (${mandatoryDrives.size} drives, driveSyncs.size=${driveSyncs.size})"
        }
        mandatoryDrives.forEach { (driveId, label) -> mountDrive(driveId, label) }
        Logger.i(tag = "DriveSync") {
            "ensureMandatoryMounted done (driveSyncs.size=${driveSyncs.size})"
        }
    }

    suspend fun start() {
        // getActiveCredentials() + null-check instead of requireActiveCredentials()
        // so a logout race can't crash the caller. Not all callers try-catch this.
        val credentials = credentialsManager.getActiveCredentials() ?: run {
            Logger.w(tag = "DriveSync") {
                "start() skipped — no active credentials (driveSyncs.size=${driveSyncs.size})"
            }
            return
        }
        Logger.i(tag = "DriveSync") {
            "start() (isRunning before=$_isRunning, driveSyncs.size=${driveSyncs.size}, " +
                "identity=${credentials.domain})"
        }

        // Catch-up loop: anything in [mandatoryDrives] that wasn't already registered
        // via [ensureMandatoryMounted] (e.g. a test that goes straight to start()) is
        // mounted here as a safety net. expectFreshCursors signalling moves into the
        // per-mount path via mountDrive() so it applies to mandatory and optional
        // drives uniformly.
        ensureMandatoryMounted()

        _isRunning = true
        expectFreshCursors = false
        Logger.i(tag = "DriveSync") {
            "start() done (isRunning=true, driveSyncs.size=${driveSyncs.size})"
        }
        // Drives registered via mountDrive() while _isRunning was false (bootstrap
        // pre-mounts, add-on activations during a paused window, or the mandatory
        // ensureMandatoryMounted above) sit in driveSyncs with their initial sync
        // deferred. The caller is expected to follow start() with syncAll(), which
        // iterates the full map and kicks each. Both production callers
        // (AuthConnectionCoordinator and BackgroundSyncOrchestrator) already do this.
    }

    suspend fun syncAll() {
        if (!_isRunning) {
            Logger.w(tag = "DriveSync") {
                "syncAll() skipped — not running (driveSyncs.size=${driveSyncs.size})"
            }
            return
        }
        val snapshot = driveSyncsMutex.withLock { driveSyncs.values.toList() }
        val jobs = snapshot.mapNotNull { it.sync() }
        jobs.joinAll()
    }

    suspend fun syncAllFailed() {
        val failedIds = _driveStatuses.value
            .filter { (_, status) -> status.state is DriveState.Failed }
            .keys

        val jobs = driveSyncsMutex.withLock { failedIds.mapNotNull { driveSyncs[it] } }
            .mapNotNull { it.sync() }
        jobs.joinAll()
    }

    fun syncDrive(driveId: Uuid) {
        if (!_isRunning) {
            Logger.w(tag = "DriveSync") {
                "syncDrive($driveId) skipped — not running (driveSyncs.size=${driveSyncs.size})"
            }
            return
        }
        val d = driveSyncs[driveId] ?: throw Exception("syncDrive() invalid driveId: $driveId")
        d.sync()
    }

    fun pause() {
        Logger.i(tag = "DriveSync") {
            "pause() (isRunning before=$_isRunning, driveSyncs.size=${driveSyncs.size})"
        }
        _isRunning = false
        driveSyncs.values.forEach { it.cancel() }
        _driveStatuses.update { statuses ->
            statuses.mapValues { (_, status) ->
                if (status.state is DriveState.Synchronizing)
                    status.copy(state = DriveState.Completed())
                else status
            }
        }
    }

    suspend fun stop() {
        Logger.i(tag = "DriveSync") {
            "stop() (isRunning before=$_isRunning, driveSyncs.size=${driveSyncs.size})"
        }
        _isRunning = false
        val old = driveSyncsMutex.withLock {
            val snapshot = driveSyncs
            driveSyncs = emptyMap()
            snapshot
        }

        old.values.forEach { it.cancel() }
        _driveStatuses.update { emptyMap() }
    }

    /**
     * Mounts a drive into the sync engine. Idempotent — repeat calls for the
     * same `driveId` are no-ops and return `false`.
     *
     * Splits cleanly into "register" (in-memory bookkeeping — always runs once
     * credentials exist) and "kick a sync" (network I/O — only runs while the
     * manager is in the running state). A drive registered while paused/not-
     * yet-started is picked up by the next [start]'s catch-up loop.
     *
     * @return `true` if this call actually registered a new drive, `false` if
     *   the drive was already mounted (or could not be mounted because
     *   credentials are missing / construction failed). Callers like
     *   [AuthConnectionCoordinator.mountDrive] use the return value to decide
     *   whether to trigger a WebSocket-subscription refresh — redundant mount
     *   calls (e.g. from a ViewModel reacting to driveStatuses) must NOT cause
     *   a reconnect, or they will tear down an in-flight WS handshake.
     */
    suspend fun mountDrive(driveId: Uuid, label: String): Boolean {
        // getActiveCredentials() instead of requireActiveCredentials() — a logout
        // race during add-on activation should be a deferred mount, not a crash.
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: run {
            Logger.w(tag = "DriveSync") {
                "mountDrive($driveId, $label) skipped — no active credentials"
            }
            return false
        }

        // expectFreshCursors is set by clearStorage() (logout) and consumed by the
        // next batch of mounts. Applies to mandatory AND optional drives so a
        // surviving cursor on any of them is logged loudly. start() resets the flag
        // once all post-login mounts are done.
        val freshLogin = expectFreshCursors

        // The alreadyExists check and the insertion happen inside the same
        // mutex block so two parallel mountDrive calls for the same driveId
        // can't both pass the guard and register twice. Withdraws the
        // alreadyExists race that was previously possible.
        val sync = driveSyncsMutex.withLock {
            val sizeBefore = driveSyncs.size
            if (driveSyncs.containsKey(driveId)) {
                Logger.w(tag = "DriveSync") {
                    "mountDrive($driveId, $label) — already mounted, no-op (driveSyncs.size=$sizeBefore)"
                }
                return false
            }
            val newSync = try {
                DriveSync(
                    identityId = identityId,
                    driveId = driveId,
                    driveQueryProvider = driveQueryProvider,
                    databaseManager = databaseManager,
                    eventBus = eventBus,
                    scope = scope,
                    expectFreshCursor = freshLogin,
                )
            } catch (e: CancellationException) {
                // Don't swallow cancellation — let the caller's scope tear down cleanly.
                throw e
            } catch (e: Exception) {
                Logger.e(tag = "DriveSync", throwable = e) {
                    "mountDrive($driveId, $label) — construction failed: ${e.message}"
                }
                return false
            }
            driveSyncs = driveSyncs + (driveId to newSync)
            Logger.i(tag = "DriveSync") {
                "mountDrive($driveId, $label) registered " +
                    "(driveSyncs.size=$sizeBefore -> ${sizeBefore + 1}, freshLogin=$freshLogin, isRunning=$_isRunning)"
            }
            newSync
        }
        _driveStatuses.update { it + (driveId to DriveStatus(driveId, label, DriveState.Initialized)) }

        // Defer the network kick if the manager isn't running yet — start()'s
        // catch-up loop will pick it up when isRunning flips true.
        if (_isRunning) sync.sync()
        return true
    }

    /**
     * Removes a drive from active sync for the current session. Does NOT modify [DriveRegistry] —
     * the drive will be attempted again on the next app startup. Use this for session-level
     * permission failures (403) so the sync indicator clears without altering user configuration.
     */
    suspend fun unmountDrive(driveId: Uuid) {
        val sync = driveSyncsMutex.withLock {
            val s = driveSyncs[driveId]
            driveSyncs = driveSyncs - driveId
            s
        } ?: return
        sync.cancel()
        _driveStatuses.update { it - driveId }
        Logger.i(tag = "DriveSync") { "unmountDrive($driveId)" }
    }

    // Runs under NonCancellable because the typical caller is
    // YouAuthFlowManager.logout() on SettingsViewModel.viewModelScope, and that scope is
    // cancelled as soon as the auth-state flip to Unauthenticated tears the settings
    // screen down. Without this, the wipe would be interrupted mid-flight and leave the
    // KeyValue / Outbox / AppNotifications / ConnectionCache tables stale across logins.
    suspend fun clearStorage() = withContext(NonCancellable) {
        val snapshot = driveSyncsMutex.withLock { driveSyncs.values.toList() }

        // Zero per-drive in-memory state (cursor fields) before the SQL wipe so no
        // DriveSync can lazily re-materialize a cursor from a row that's about to be
        // dropped.
        snapshot.forEach { it.resetInMemoryState() }

        // One DROP + CREATE + VACUUM of every table in OdinDatabase. Replaces the
        // previous per-table deleteAll() chain, which was observed leaving Outbox
        // rows across logout/login with their retry counters intact. DROP is the
        // unforgeable variant — open transactions, stale caches, and stray driver
        // references can't carry rows across it. Verification probes inside
        // wipeAndRecreate() log an error if either of those loopholes actually fires.
        databaseManager.wipeAndRecreate()

        // Signal the next start() that any cursor it finds is a bug.
        expectFreshCursors = true
    }
}
