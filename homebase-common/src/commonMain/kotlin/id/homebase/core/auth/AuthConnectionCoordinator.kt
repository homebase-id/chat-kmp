package id.homebase.core.auth

import androidx.compose.runtime.Immutable
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.websockets.OdinWebSocketClient
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.core.config.syncDrives
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthConnectionCoordinator(
    private val credentialsManager: CredentialsManager,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val youAuthFlowManager: YouAuthFlowManager,
    private val driveSyncManager: DriveSyncManager,
    private val eventBus: EventBus
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var wsClient: OdinWebSocketClient? = null

    private val _connectionState = MutableStateFlow(AuthConnectionState())
    val connectionState: StateFlow<AuthConnectionState> = _connectionState.asStateFlow()

    init {
        scope.launch {
            youAuthFlowManager.authState.collect {
                onAuthStateChanged(it)
            }
        }
    }

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
                scope = scope,
                eventBus = eventBus,
                databaseManager = DatabaseManager.appDb,
                drives = syncDrives,
                onConnected = {
                    _connectionState.update { it.copy(isConnected = true) }
                    scope.launch {
                        driveSyncManager.syncAll()
                        _connectionState.update { it.copy(isDoingInitialConnection = false) }
                    }
                },
                onDisconnected = {
                    _connectionState.update { it.copy(isConnected = false, isDoingInitialConnection = false) }
                    driveSyncManager.pause()
                }
            ).also { it.start() }
    }

    private fun disconnect() {
        _connectionState.update { it.copy(isConnected = false, isDoingInitialConnection = true) }
        wsClient?.close()
        wsClient = null
        driveSyncManager.stop()
    }
}

@Immutable
data class AuthConnectionState(
    val isDoingInitialConnection: Boolean = true,
    val isConnected: Boolean = false,
)
