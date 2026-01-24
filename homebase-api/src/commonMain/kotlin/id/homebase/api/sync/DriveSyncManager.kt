package id.homebase.homebasekmppoc.prototype.lib.drives

import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.sync.DriveSync
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.*
import kotlin.uuid.Uuid

class DriveSyncManager(
    private val drives: List<DriveSync>,  // TODO: Todd <- or is this list a global singleton and not a parameter?
    private val driveQueryProvider: DriveQueryProvider,
    private val databaseManager: DatabaseManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
)
{
    /**
     * Called when websocket reports "connected"
     * Fire-and-forget safe.
     */
    fun onConnected(
        identityId: Uuid,
        drives: List<DriveSync>
    ) {
        syncAll(identityId, drives)
    }

    private fun syncAll(
        identityId: Uuid,
        drives: List<DriveSync>
    ) {
        // Any sync jobs created will be F&F
        for (drive in drives) {
            val job = drive.sync()
        }
    }

    fun cancelAll(drives: List<DriveSync>) {
        for (drive in drives) {
             drive.cancel() // Not active, see function
        }
    }
}
