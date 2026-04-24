package id.homebase.api.sync

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
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
    private val drives: Map<Uuid, String>,
) {
    // Immutable map reference — always replaced, never mutated in-place, preventing CME.
    // All writes are serialized via driveSyncsMutex, which provides the happens-before
    // guarantee needed for non-mutex readers (syncDrive, pause, clearStorage).
    private var driveSyncs: Map<Uuid, DriveSync> = emptyMap()
    private val driveSyncsMutex = Mutex()
    @kotlin.concurrent.Volatile private var isRunning = false

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
                    is BackendEvent.DriveEvent.BatchReceived -> {
                        // Only update progress count during an active sync (Started already fired).
                        // Don't let stray BatchReceived from OptimisticWriter transition
                        // a Completed/Failed drive back to Synchronizing.
                        // Ignore events whose totalCount is below the count we've already
                        // shown — real-time WebSocket pushes and optimistic writes emit
                        // totalCount=1 and would otherwise snap the progress bar backwards
                        // mid-backfill.
                        val current = _driveStatuses.value[event.driveId]?.state
                        if (current is DriveState.Synchronizing && event.totalCount >= current.count) {
                            updateState(event.driveId) {
                                it.copy(state = DriveState.Synchronizing(count = event.totalCount))
                            }
                        }
                    }
                    is BackendEvent.DriveEvent.Stopped -> when (val r = event.result) {
                        is BackendEvent.DriveResult.Success -> updateState(event.driveId) {
                            it.copy(state = DriveState.Completed(totalCount = event.totalCount))
                        }
                        is BackendEvent.DriveResult.Failure -> {
                            updateState(event.driveId) {
                                it.copy(state = DriveState.Failed(r.errorMessage))
                            }
                            Logger.w { "DriveSyncManager: drive ${event.driveId} failed: ${r.errorMessage}, scheduling retry in 1s" }
                            scope.launch {
                                delay(1000L)
                                Logger.i { "DriveSyncManager: retrying drive ${event.driveId}" }
                                driveSyncsMutex.withLock { driveSyncs[event.driveId] }?.sync()
                            }
                        }
                        is BackendEvent.DriveResult.PermissionDenied -> {
                            Logger.w { "DriveSyncManager: drive ${event.driveId} denied (403) — unmounting for this session" }
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

    suspend fun start() {
        val credentials = credentialsManager.requireActiveCredentials()
        val identityId = credentials.getIdentityId()

        val freshLogin = expectFreshCursors
        expectFreshCursors = false

        drives.forEach { (driveId, label) ->
            val alreadyExists = driveSyncsMutex.withLock { driveSyncs.containsKey(driveId) }
            if (alreadyExists) {
                Logger.w { "DriveSync for drive=$driveId already exists, skipping" }
                return@forEach
            }

            runCatching {
                DriveSync(
                    identityId = identityId,
                    driveId = driveId,
                    driveQueryProvider = driveQueryProvider,
                    databaseManager = databaseManager,
                    eventBus = eventBus,
                    scope = scope,
                    expectFreshCursor = freshLogin,
                )
            }.onSuccess { sync ->
                driveSyncsMutex.withLock { driveSyncs = driveSyncs + (driveId to sync) }
                _driveStatuses.update { current ->
                    current + (driveId to DriveStatus(driveId, label, DriveState.Initialized))
                }
            }.onFailure { e ->
                Logger.e(
                    "Failed to create DriveSync for drive=$driveId: ${e.message}",
                    e
                )
            }
        }
        isRunning = true
    }

    suspend fun syncAll() {
        if (!isRunning) { Logger.w { "syncAll() skipped — not running" }; return }
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
        if (!isRunning) { Logger.w { "syncDrive() skipped — not running" }; return }
        val d = driveSyncs[driveId] ?: throw Exception("syncDrive() invalid driveId: $driveId")
        d.sync()
    }

    fun pause() {
        isRunning = false
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
        isRunning = false
        val old = driveSyncsMutex.withLock {
            val snapshot = driveSyncs
            driveSyncs = emptyMap()
            snapshot
        }

        old.values.forEach { it.cancel() }
        _driveStatuses.update { emptyMap() }
    }

    /**
     * Dynamically mounts a drive into the sync engine (e.g. when an add-on is activated
     * mid-session). Starts an HTTP-polling sync immediately; real-time WebSocket push
     * notifications will arrive only after the next reconnect.
     */
    suspend fun mountDrive(driveId: Uuid, label: String) {
        if (!isRunning) { Logger.w { "mountDrive() skipped — not running" }; return }
        val alreadyExists = driveSyncsMutex.withLock { driveSyncs.containsKey(driveId) }
        if (alreadyExists) return

        val identityId = credentialsManager.requireActiveCredentials().getIdentityId()
        runCatching {
            DriveSync(identityId, driveId, driveQueryProvider, databaseManager, eventBus, scope)
        }.onSuccess { sync ->
            driveSyncsMutex.withLock { driveSyncs = driveSyncs + (driveId to sync) }
            _driveStatuses.update { it + (driveId to DriveStatus(driveId, label, DriveState.Initialized)) }
            sync.sync()
        }.onFailure { e ->
            Logger.e("DriveSyncManager: mountDrive failed for $driveId: ${e.message}", e)
        }
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
        Logger.i { "DriveSyncManager: unmounted drive $driveId" }
    }

    // Runs under NonCancellable because the typical caller is
    // YouAuthFlowManager.logout() on SettingsViewModel.viewModelScope, and that scope is
    // cancelled as soon as the auth-state flip to Unauthenticated tears the settings
    // screen down. Without this, the wipe would be interrupted mid-flight and leave the
    // KeyValue / Outbox / AppNotifications / ConnectionCache tables stale across logins.
    suspend fun clearStorage() = withContext(NonCancellable) {
        val snapshot = driveSyncsMutex.withLock { driveSyncs.values.toList() }

        coroutineScope {
            snapshot
                .map { sync -> launch { sync.clearStorage() } }
                .joinAll()
        }

        // Wipe identity-scoped tables once, after every per-drive clear has finished.
        // Anything stored here is tied to the logged-out identity and must not leak
        // into the next session.
        //  - KeyValue       (sync cursors — the reason this method exists)
        //  - Outbox         (pending uploads bound to drives we just wiped)
        //  - AppNotifications
        //  - ConnectionCache
        databaseManager.keyValue.deleteAll()
        databaseManager.outbox.deleteAll()
        databaseManager.appNotifications.deleteAllRows()
        databaseManager.connectionCache.deleteAllRows()

        // Reclaim the disk space from the large DELETEs above. Logout is rare and
        // already a "please wait" moment — paying the VACUUM cost here keeps login and
        // normal operation fast.
        databaseManager.vacuum()

        // Signal the next start() that any cursor it finds is a bug.
        expectFreshCursors = true
    }
}
