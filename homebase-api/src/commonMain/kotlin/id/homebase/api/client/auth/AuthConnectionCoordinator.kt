package id.homebase.api.client.auth

import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.sync.DriveSync
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.homebasekmppoc.lib.config.chatTargetDrive
import id.homebase.api.youauth.YouAuthState
import id.homebase.homebasekmppoc.prototype.lib.drives.DriveSyncManager
import id.homebase.api.client.eventbus.appEventBus
import id.homebase.api.client.websockets.OdinWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.uuid.Uuid

class AuthConnectionCoordinator(
    val credentialsManager: CredentialsManager,
    val driveQueryProvider: DriveQueryProvider
) {
    private val identityId = Uuid.parse("7b1be23b-48bb-4304-bc7b-db5910c09a92") // TODO: Todd <- Had to do this, identityId shouldn't be in a suspend function. It should be globally easily available anytime someone is logged in
    private var wsClient: OdinWebSocketClient? = null
    private val ioScope = CoroutineScope(Dispatchers.Default)

    private val targetDriveDrives = listOf(chatTargetDrive)  // TODO: <-- two drive lists of different types

    private val driveSyncDrives = listOf(  // TODO: Todd <- this is somewhat ugly. Also the two lists. But these must be singletons, I don't think they should be created here
        DriveSync(
            identityId = identityId,
            driveId = chatTargetDrive.alias,
            driveQueryProvider = driveQueryProvider,
            databaseManager = DatabaseManager.appDb,
            eventBus = appEventBus,
            scope = ioScope
        )
    )

    // There should be only one singleton instance of each DriveSync(), the list can't be with
    // duplicates for the same driveId
    private val driveSyncManager = DriveSyncManager(
        drives = driveSyncDrives,
        driveQueryProvider = driveQueryProvider,
        databaseManager = DatabaseManager.appDb,
        scope = ioScope,
        eventBus = appEventBus
    )

    suspend fun onAuthStateChanged(state: YouAuthState) {
        when (state) {
            is YouAuthState.Authenticated -> connect(state)
            else -> disconnect()
        }
    }

    suspend fun onNetworkChanged(isOnline: Boolean) {
        if (!isOnline) disconnect()
        else wsClient?.start()
    }

    private fun connect(state: YouAuthState.Authenticated) {
        if (wsClient != null) return

        wsClient = OdinWebSocketClient(
            credentialsManager,
            ioScope,
            appEventBus,
            DatabaseManager.appDb,
            targetDriveDrives,
            onConnected = {
                handleWsConnect()
            },
            onDisconnected = {
                driveSyncManager.cancelAll(driveSyncDrives)
            }
        ).also { it.start() }
    }

    private fun handleWsConnect() {
        driveSyncManager.onConnected(
            identityId = identityId,
            drives = driveSyncDrives
        )
    }


    private fun disconnect() {
        wsClient?.close()
        wsClient = null
    }
}
