package id.homebase.api.sync

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.CoroutineScope
import kotlin.uuid.Uuid

class DriveSyncManager(
    private val driveQueryProvider: DriveQueryProvider,
    private val credentialsManager: CredentialsManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {
    private val driveSyncs = mutableMapOf<Uuid, DriveSync>()

    suspend fun start(drives: List<Uuid>) {
        val credentials = credentialsManager.getActiveCredentials()
            ?: return

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
        driveSyncs.values.forEach { it.sync() }
    }

    fun stop() {
        driveSyncs.values.forEach { it.cancel() }
        driveSyncs.clear()
    }

    suspend fun clearStorage() {
        driveSyncs.values.forEach { it.clearStorage() }
    }

    fun getActiveDriveSyncs(): List<DriveSync> = driveSyncs.values.toList()
}
