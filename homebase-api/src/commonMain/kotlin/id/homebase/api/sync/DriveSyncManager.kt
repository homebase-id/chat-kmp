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
    private var driveSyncs: List<DriveSync> = emptyList()

    suspend fun start(
        drives: List<Uuid>
    ) {
        if (driveSyncs.isNotEmpty()) return
        val credentials = credentialsManager.getActiveCredentials()
            ?: return

        val identityId = credentials.getIdentityId()

        driveSyncs = drives.mapNotNull { drive ->
            runCatching {
                DriveSync(
                    identityId = identityId,
                    driveId = drive,
                    driveQueryProvider = driveQueryProvider,
                    databaseManager = DatabaseManager.appDb,
                    eventBus = eventBus,
                    scope = scope
                )
            }.onFailure { e ->
                Logger.e(
                    "Failed to create DriveSync for drive=${drive}: ${e.message}",
                    e
                )
            }.getOrNull()
        }
    }

    fun syncAll() {
        driveSyncs.forEach { it.sync() }
    }

    fun stop() {
        driveSyncs.forEach { it.cancel() }
        driveSyncs = emptyList()
    }

    suspend fun clearStorage(){
        driveSyncs.forEach { it.clearStorage() }
    }

    fun getActiveDriveSyncs(): List<DriveSync> = driveSyncs
}
