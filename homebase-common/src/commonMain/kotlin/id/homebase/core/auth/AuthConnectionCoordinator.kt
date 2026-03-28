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
import id.homebase.core.avatars.AppConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    /**
     * True when the WebSocket is connected AND the server handshake has completed
     * successfully. This is the canonical online check — use this instead of reading
     * [connectionState] directly when you only need a boolean online/offline signal.
     *
     * Note: "online" here means the app can communicate with the Homebase server.
     * It does NOT track per-contact social-graph connection state.
     */
    val isOnline: StateFlow<Boolean> = connectionState
        .map { it.isConnected }
        .stateIn(scope, SharingStarted.Eagerly, false)

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

        driveSyncManager.start()

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
                    outboxSync.setOnline(true)
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
                    outboxSync.setOnline(false)
                    _connectionState.update { it.copy(isConnected = false, isConnecting = false) }
                    driveSyncManager.pause()
                },
                onConnectError = {
                    outboxSync.setOnline(false)
                    _connectionState.update { it.copy(isConnected = false, isConnecting = false) }
                }
            ).also { it.start() }
    }

    private suspend fun disconnect() {
        outboxSync.setOnline(false)
        _connectionState.update { it.copy(isConnected = false, isConnecting = false) }
        wsClient?.close()
        wsClient = null
        driveSyncManager.stop()
    }
}

@Immutable
data class AuthConnectionState(
    val isConnecting: Boolean = true,
    /** True only after the WebSocket TCP connection is established AND the server
     *  handshake (deviceHandshakeSuccess) has been received. False at all other times.
     *  Use [AuthConnectionCoordinator.isOnline] for a named reactive check. */
    val isConnected: Boolean = false,
)

/** Maps this state to the 3-state UI enum used by avatar indicators and UI state. */
fun AuthConnectionState.toConnectionStatus(): AppConnectionStatus = when {
    isConnected  -> AppConnectionStatus.Connected
    isConnecting -> AppConnectionStatus.Connecting
    else         -> AppConnectionStatus.Disconnected
}
