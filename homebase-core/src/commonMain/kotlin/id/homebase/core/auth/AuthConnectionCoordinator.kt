package id.homebase.core.auth

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.websockets.OdinWebSocketClient
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.core.config.syncDrives
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class AuthConnectionCoordinator(
    private val credentialsManager: CredentialsManager,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val driveSyncManager: DriveSyncManager,
    private val eventBus: EventBus
) {
    private val ioScope = CoroutineScope(Dispatchers.Default)
    private var wsClient: OdinWebSocketClient? = null

    suspend fun onAuthStateChanged(state: YouAuthState) {
        when (state) {
            is YouAuthState.Authenticated -> {
                connect()
                loadProfile()
            }

            else -> disconnect()
        }
    }

    private suspend fun loadProfile() {
        val odinId = credentialsManager.requireActiveCredentials().domain
        ownerSessionRepository.load(odinId)
    }

    private suspend fun connect() {
        if (wsClient != null) return

        val driveIds = syncDrives.map { it.alias }

        driveSyncManager.start(
            drives = driveIds
        )

        wsClient =
            OdinWebSocketClient(
                credentialsManager = credentialsManager,
                driveSyncManager = driveSyncManager,
                scope = ioScope,
                eventBus = eventBus,
                databaseManager = DatabaseManager.appDb,
                drives = syncDrives,
                onConnected = { driveSyncManager.syncAll() },
                onDisconnected = { driveSyncManager.pause() }
            ).also { it.start() }
    }

    private fun disconnect() {
        wsClient?.close()
        wsClient = null
        driveSyncManager.stop()
    }
}
