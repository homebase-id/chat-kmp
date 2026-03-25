package id.homebase.core.auth

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.websockets.OdinWebSocketClient
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.core.config.syncLabeledDrives
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthConnectionCoordinator(
    private val credentialsManager: CredentialsManager,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val youAuthFlowManager: YouAuthFlowManager,
    private val driveSyncManager: DriveSyncManager,
    private val outboxSync: OutboxSync,
    private val eventBus: EventBus,
    private val databaseManager: DatabaseManager
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
        scope.launch {
            eventBus.events.collectLatest {
                if (it is BackendEvent.ConnectionOnline) {
                    // When offline always refetch profile if name is not set
                    if (ownerSessionRepository.user.value?.firstName == null) {
                        loadProfile()
                    }
                }
            }
        }
        scope.launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.Connecting) {
                    _connectionState.update { it.copy(isConnecting = true) }
                }
            }
        }
    }

    suspend fun onAuthStateChanged(state: YouAuthState) {
        when (state) {
            is YouAuthState.Authenticated -> {
                connect()
                loadProfile()
            }
            is YouAuthState.Initializing -> {
                // ignore
            }
            else -> {
                disconnect()
            }
        }
    }

    private fun loadProfile() {
        scope.launch {
            val odinId = credentialsManager.requireActiveCredentials().domain
            ownerSessionRepository.load(odinId)
        }
    }

    private suspend fun connect() {
        if (wsClient != null) return

        driveSyncManager.start(drives = syncLabeledDrives.associate { it.drive.alias to it.label })

        wsClient =
            OdinWebSocketClient(
                credentialsManager = credentialsManager,
                driveSyncManager = driveSyncManager,
                scope = scope,
                eventBus = eventBus,
                databaseManager = databaseManager,
                drives = syncLabeledDrives.map { it.drive },
                onConnected = {
                    _connectionState.update { it.copy(isConnected = true) }
                    scope.launch {
                        try {
                            driveSyncManager.syncAll()
                        } catch (e: Exception) {
                            Logger.e(e) { "syncAll() failed on connect" }
                        } finally {
                            if (_connectionState.value.isConnected) {
                                _connectionState.update { it.copy(isConnecting = false) }
                            }
                            outboxSync.clearCheckout()
                            outboxSync.send()
                        }
                    }
                },
                onDisconnected = {
                    _connectionState.update { it.copy(isConnected = false, isConnecting = false) }
                    driveSyncManager.pause()
                },
                onConnectError = {
                    _connectionState.update { it.copy(isConnected = false, isConnecting = false) }
                }
            ).also { it.start() }
    }

    private fun disconnect() {
        _connectionState.update { it.copy(isConnected = false, isConnecting = false) }
        wsClient?.close()
        wsClient = null
        driveSyncManager.stop()
    }
}

@Immutable
data class AuthConnectionState(
    val isConnecting: Boolean = true,
    val isConnected: Boolean = false,
)
