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
import id.homebase.core.config.LabeledDrive
import id.homebase.core.config.mandatorySyncDrives
import id.homebase.core.sync.DriveRegistry
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
import kotlin.uuid.Uuid

class AuthConnectionCoordinator(
    private val credentialsManager: CredentialsManager,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val youAuthFlowManager: YouAuthFlowManager,
    private val driveSyncManager: DriveSyncManager,
    private val outboxSync: OutboxSync,
    private val eventBus: EventBus,
    private val databaseManager: DatabaseManager,
    private val driveRegistry: DriveRegistry,
    private val onPostAuthenticated: () -> Unit = {},
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var wsClient: OdinWebSocketClient? = null

    // Coalesces bursts of mountDrive/unmountDrive calls into a single WebSocket reconnect.
    // [OdinWebSocketClient] freezes its drive-subscription list at construction time, so we
    // have to close and reopen the socket to pick up a registry change mid-session. If a user
    // activates two add-ons back-to-back, we want one reconnect, not two.
    private val refreshWsSubscription = DebouncedAction(scope, REFRESH_DEBOUNCE_MS) {
        reconnectWebSocket()
    }

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
                onPostAuthenticated()
                // Resolve the cross-device registry: local DB on cold boot (free), or one
                // targeted server fetch on fresh login. Either way we have the canonical
                // list before opening the WebSocket, so the first WS connect already
                // subscribes to the full set — no late observer-driven reconnect.
                val initialDrives = driveRegistry.bootstrap()
                for (drive in initialDrives) {
                    driveSyncManager.mountDrive(drive.drive.alias, drive.label)
                }
                connect(extraDrives = initialDrives)
                // Observe cross-device registry changes. We seed the diff baseline with the
                // bootstrap result so the chat-drive sync that later writes the same file
                // into the local index doesn't trigger a spurious onMount for drives that
                // are already mounted.
                driveRegistry.start(
                    onMount = { drive -> mountDrive(drive, persist = false) },
                    onUnmount = { driveId -> unmountDrive(driveId, persist = false) },
                    initialBaseline = initialDrives.mapTo(HashSet()) { it.drive.alias },
                )
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
            // Use getActiveCredentials() rather than requireActiveCredentials() —
            // a BackendEvent.ConnectionOnline racing with logout would otherwise
            // throw IllegalStateException on a scope with no exception handler,
            // crashing the process.
            val credentials = credentialsManager.getActiveCredentials() ?: run {
                Logger.d { "loadProfile: skipping — no active credentials" }
                return@launch
            }
            ownerSessionRepository.load(credentials.domain)
        }
    }

    /**
     * Initialises and starts the WebSocket client for this session.
     * Returns immediately — the connection is established asynchronously.
     * React to successful connection via the [onConnected] callback below,
     * which fires only after the server handshake (deviceHandshakeSuccess) completes.
     *
     * Guarded by [wsClient] null-check so it is safe to call repeatedly; only
     * the first call per session does anything.
     *
     * @param extraDrives optional drives to subscribe to in addition to
     *   [mandatorySyncDrives]. The fresh-login path passes the bootstrap result
     *   directly (because the local DB may not contain the registry file yet); all
     *   other call sites — mid-session reconnects in particular — fall through to
     *   `driveRegistry.loadDrives()`, which reads the (now-populated) local index.
     */
    private suspend fun connect(extraDrives: List<LabeledDrive>? = null) {
        if (wsClient != null) return

        val optionalDrives = extraDrives ?: driveRegistry.loadDrives()
        _connectionState.update { it.copy(isConnecting = true) }
        wsClient =
            OdinWebSocketClient(
                credentialsManager = credentialsManager,
                driveSyncManager = driveSyncManager,
                scope = scope,
                eventBus = eventBus,
                databaseManager = databaseManager,
                drives = (mandatorySyncDrives + optionalDrives).map { it.drive },
                // Fires asynchronously once the server handshake has completed.
                // We mark the connection state and then run post-connect setup in a
                // background coroutine:
                //   1. driveSyncManager.start/syncAll() — catch up on inbound drive changes.
                //   2. outboxSync.clearCheckout()       — clear any stale checked-out items
                //                                         from before the disconnect.
                //   3. outboxSync.setOnline(true)       — only enable outbox sending AFTER
                //                                         sync and cleanup are done, ensuring
                //                                         a clean send window.
                //   4. outboxSync.send()                — flush the outbox queue.
                onConnected = {
                    _connectionState.update { it.copy(isConnected = true) }
                    scope.launch {
                        try {
                            // start() must be called on every (re)connect — not just the first —
                            // because pause() sets isRunning=false on disconnect, and syncAll()
                            // would silently skip if isRunning is still false.
                            driveSyncManager.start()

                            // Flush the server inbox before syncing.  While connected the
                            // server pushes inboxItemReceived notifications in real-time,
                            // but those notifications are NOT replayed after a reconnect.
                            // Without this call, items that arrived while we were offline
                            // stay in the inbox and QueryBatch returns 0 records.
                            wsClient?.processAllInboxes()

                            driveSyncManager.syncAll()
                        } catch (e: Exception) {
                            Logger.e(e) { "syncAll() failed on connect" }
                        } finally {
                            if (_connectionState.value.isConnected) {
                                _connectionState.update { it.copy(isConnecting = false) }
                            }
                            outboxSync.clearCheckout()
                            outboxSync.setOnline(true)
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

    fun setForeground(foreground: Boolean) {
        wsClient?.isInForeground = foreground
    }

    /**
     * Activate an optional add-on drive. When [persist] is true (default), uploads a
     * registry marker to the Chat drive so the activation syncs to the user's other
     * devices. Hot-mounts the drive in [DriveSyncManager] (HTTP polling starts
     * immediately) and schedules a debounced WebSocket reconnect so real-time push
     * arrives within [REFRESH_DEBOUNCE_MS]. Multiple rapid calls coalesce.
     *
     * Pass `persist = false` when the activation originated elsewhere (e.g. another
     * device already uploaded the marker, and the Chat-drive observer surfaced it
     * locally) — persisting again would be a no-op on the server but wastes work.
     */
    suspend fun mountDrive(drive: LabeledDrive, persist: Boolean = true) {
        if (persist) driveRegistry.addDrive(drive)
        driveSyncManager.mountDrive(drive.drive.alias, drive.label)
        refreshWsSubscription.trigger()
    }

    /**
     * Deactivate an optional add-on drive. When [persist] is true (default), hard-deletes
     * the registry marker from the Chat drive so the removal syncs to other devices.
     * Unmounts from [DriveSyncManager] and schedules a debounced WebSocket reconnect.
     *
     * Intended for user-initiated removals and for the Chat-drive observer's unmount
     * callback (which passes `persist = false` because the deletion originated elsewhere).
     * The 403/PermissionDenied auto-unmount in [DriveSyncManager] bypasses this path on
     * purpose — re-subscribing would just get rejected again, and we do NOT want to
     * propagate a permission-denied condition as a registry deletion to other devices.
     */
    suspend fun unmountDrive(driveId: Uuid, persist: Boolean = true) {
        if (persist) driveRegistry.removeDrive(driveId)
        driveSyncManager.unmountDrive(driveId)
        refreshWsSubscription.trigger()
    }

    // Close the current WebSocket and open a new one. [connect] reads the DriveRegistry
    // fresh each call, so the new socket's drive subscription reflects the latest mount/
    // unmount state. Called via [refreshWsSubscription] after the debounce window elapses.
    // No-op when [wsClient] is null: logged-out or pre-auth bursts don't need a reconnect —
    // the next organic [connect] call (on login or reconnect) will read the fresh registry.
    private suspend fun reconnectWebSocket() {
        val old = wsClient ?: return
        wsClient = null
        old.close()
        connect()
    }

    private suspend fun disconnect() {
        refreshWsSubscription.cancel()
        driveRegistry.stop()
        outboxSync.setOnline(false)
        // Keep isConnecting = true so the next login cycle correctly starts in
        // StartupState.Loading.  While logged out the auth state is Unauthenticated,
        // so isConnecting has no visible effect on the UI.
        _connectionState.update { it.copy(isConnected = false, isConnecting = true) }
        wsClient?.close()
        wsClient = null
        driveSyncManager.stop()
    }

    companion object {
        private const val REFRESH_DEBOUNCE_MS = 500L
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
