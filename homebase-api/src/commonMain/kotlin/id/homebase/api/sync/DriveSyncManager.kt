package id.homebase.api.sync

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class DriveSyncManager(
    private val driveQueryProvider: DriveQueryProvider,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {
    private val driveSyncs = mutableMapOf<Uuid, DriveSync>()

    suspend fun start(drives: List<Uuid>) {
        val credentials = credentialsManager.requireActiveCredentials()
        val identityId = credentials.getIdentityId()

        drives.forEach { driveId ->
            if (driveSyncs.containsKey(driveId)) {
                Logger.w { "DriveSync for drive=$driveId already exists, skipping" }
                return@forEach
            }

            runCatching {
                DriveSync(
                    identityId = identityId,
                    driveId = driveId,
                    driveQueryProvider = driveQueryProvider,
                    databaseManager = DatabaseManager.appDb,
                    eventBus = eventBus,
                    scope = scope
                )
            }.onSuccess { sync ->
                driveSyncs[driveId] = sync
            }.onFailure { e ->
                Logger.e(
                    "Failed to create DriveSync for drive=$driveId: ${e.message}",
                    e
                )
            }
        }
    }

    fun syncAll() {
        val snapshot = driveSyncs.values.toList()
        snapshot.forEach { it.sync() }
    }

    fun stop() {
        val snapshot = driveSyncs.values.toList()
        snapshot.forEach { it.cancel() }
        driveSyncs.clear()
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
