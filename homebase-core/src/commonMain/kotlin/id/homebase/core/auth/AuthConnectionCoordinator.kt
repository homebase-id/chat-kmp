package id.homebase.core.auth

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.query.DriveQueryProvider
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.websockets.OdinWebSocketClient
import id.homebase.api.sync.DriveSync
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.chat.config.syncDrives
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.uuid.Uuid

class AuthConnectionCoordinator(
    private val credentialsManager: CredentialsManager,
    private val driveQueryProvider: DriveQueryProvider,
    private val eventBus: EventBus
) {
    private val ioScope = CoroutineScope(Dispatchers.Default)
    private var wsClient: OdinWebSocketClient? = null


    /** Active session state */
    private var identityId: Uuid? = null
    private var driveSyncs: List<DriveSync> = emptyList()

    suspend fun onAuthStateChanged(state: YouAuthState) {
        when (state) {
            is YouAuthState.Authenticated -> connect()
            else -> disconnect()
        }
    }

    private suspend fun connect() {
        if (wsClient != null) return

        val credentials = credentialsManager.getActiveCredentials()
            ?: return

        identityId = credentials.getIdentityId()

        driveSyncs = syncDrives.mapNotNull { targetDrive ->
            runCatching {
                DriveSync(
                    identityId = identityId!!,
                    driveId = targetDrive.alias,
                    driveQueryProvider = driveQueryProvider,
                    databaseManager = DatabaseManager.appDb,
                    eventBus = eventBus,
                    scope = ioScope
                )
            }.onFailure { e ->
                Logger.e(
                    "Failed to create DriveSync for drive=${targetDrive.alias}",
                    e
                )
            }.getOrNull()
        }

        wsClient =
            OdinWebSocketClient(
                credentialsManager = credentialsManager,
                scope = ioScope,
                eventBus = eventBus,
                databaseManager = DatabaseManager.appDb,
                drives = syncDrives,
                onConnected = { handleWsConnect() },
                onDisconnected = { handleDisconnect() }
            ).also { it.start() }
    }

    private fun handleWsConnect() {
        for (drive in driveSyncs) {
            drive.sync()
        }
    }

    private fun handleDisconnect() {
        for (drive in driveSyncs) {
            drive.cancel()
        }
        driveSyncs = emptyList()
        identityId = null
    }

    private fun disconnect() {
        wsClient?.close()
        wsClient = null
        handleDisconnect()
    }
}
