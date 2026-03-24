package id.homebase.api.sync

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import kotlin.uuid.Uuid

class DriveSyncManager(
    private val driveQueryProvider: DriveQueryProvider,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val databaseManager: DatabaseManager,
) {
    private val driveSyncs = mutableMapOf<Uuid, DriveSync>()

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
                        eventBus.emit(BackendEvent.SyncAllCompleted)
                    previous is SyncState.Syncing && current is SyncState.Failed ->
                        eventBus.emit(BackendEvent.SyncAllFailed)
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
                    is BackendEvent.DriveEvent.BatchReceived -> updateState(event.driveId) {
                        it.copy(state = DriveState.Synchronizing(count = event.totalCount))
                    }
                    is BackendEvent.DriveEvent.Completed     -> updateState(event.driveId) {
                        it.copy(state = DriveState.Completed(totalCount = event.totalCount))
                    }
                    is BackendEvent.DriveEvent.Failed        -> {
                        updateState(event.driveId) {
                            it.copy(state = DriveState.Failed(event.errorMessage))
                        }
                        scope.launch {
                            delay(1000L)
                            driveSyncs[event.driveId]?.sync()
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

    suspend fun start(drives: Map<Uuid, String>) {
        val credentials = credentialsManager.requireActiveCredentials()
        val identityId = credentials.getIdentityId()

        drives.forEach { (driveId, label) ->
            if (driveSyncs.containsKey(driveId)) {
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
                    scope = scope
                )
            }.onSuccess { sync ->
                driveSyncs[driveId] = sync
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
    }

    suspend fun syncAll() {
        val snapshot = driveSyncs.values.toList()
        val jobs = snapshot.mapNotNull { it.sync() }
        jobs.joinAll()
    }

    suspend fun syncAllFailed() {
        val failedIds = _driveStatuses.value
            .filter { (_, status) -> status.state is DriveState.Failed }
            .keys

        val jobs = failedIds.mapNotNull { driveSyncs[it]?.sync() }
        jobs.joinAll()
    }

    fun syncDrive(driveId: Uuid) {
        val d = driveSyncs[driveId] ?: throw Exception("syncDrive() invalid driveId: $driveId")

        d.sync()
    }

    fun pause() {
        val snapshot = driveSyncs.values.toList()
        snapshot.forEach { it.cancel() }
    }

    fun stop() {
        val snapshot = driveSyncs.values.toList()
        snapshot.forEach { it.cancel() }
        driveSyncs.clear()
        _driveStatuses.update { emptyMap() }
    }

    fun clearStorage(): Job {
        val snapshot = driveSyncs.values.toList()

        return scope.launch {
            snapshot
                .map { sync ->
                    launch {
                        sync.clearStorage()
                    }
                }
                .joinAll()
        }
    }
}
