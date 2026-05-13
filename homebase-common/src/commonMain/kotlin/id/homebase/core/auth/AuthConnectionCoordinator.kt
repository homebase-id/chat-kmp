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
        Logger.i(tag = "AuthLifecycle") { "AuthCC: collector started" }
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
        Logger.i(tag = "AuthLifecycle") { "AuthCC: onAuthStateChanged(${state::class.simpleName})" }
        logLifecycleSnapshot("onAuthStateChanged:${state::class.simpleName}")
        when (state) {
            is YouAuthState.Authenticated -> {
                onPostAuthenticated()

                // Mount mandatory drives FIRST — before bootstrap, before any network I/O.
                // Encodes the invariant that this app's sync engine always syncs these
                // drives; nothing about the WS handshake or the registry should be able to
                // prevent them from appearing in driveStatuses.
                driveSyncManager.ensureMandatoryMounted()

                // Resolve the cross-device registry: local DB on cold boot (free), or one
                // targeted server fetch on fresh login. Either way we have the canonical
                // list before opening the WebSocket, so the first WS connect already
                // subscribes to the full set — no late observer-driven reconnect.
                val initialDrives = driveRegistry.bootstrap()
                // Pre-mount optional drives so they're in driveSyncs when
                // onConnected fires start() + syncAll(). mountDrive() defers
                // the network kick while isRunning is false; syncAll() picks
                // them up alongside the mandatory drives.
                for (drive in initialDrives) {
                    driveSyncManager.mountDrive(drive.drive.alias, drive.label)
                }
                logLifecycleSnapshot("drives mounted")
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

    /**
     * Single-line summary of session-critical state. Fired at every auth transition and
     * after each [_connectionState] mutation so a grep on `AuthLifecycle SNAPSHOT` gives
     * a complete narrative of the mount / connect / sync flow.
     */
    private fun logLifecycleSnapshot(label: String) {
        val statuses = driveSyncManager.driveStatuses.value
        val statusesSummary = if (statuses.isEmpty()) "{}" else statuses.entries.joinToString(
            prefix = "{", postfix = "}", separator = ", "
        ) { (id, s) -> "$id:${s.state::class.simpleName}" }
        val cs = _connectionState.value
        Logger.i(tag = "AuthLifecycle") {
            "AuthCC: SNAPSHOT[$label] " +
                "authState=${youAuthFlowManager.authState.value::class.simpleName} " +
                "isConnecting=${cs.isConnecting} isConnected=${cs.isConnected} " +
                "wsClient=${wsClient?.let { "WS[${it.instanceId}]" } ?: "null"} " +
                "dsmRunning=${driveSyncManager.isRunning} " +
                "driveStatuses=$statusesSummary"
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
        Logger.i(tag = "AuthLifecycle") {
            "AuthCC: connect() called (wsClient=${wsClient?.let { "WS[${it.instanceId}]" } ?: "null"}, " +
                "extraDrives=${extraDrives?.size})"
        }
        if (wsClient != null) {
            // This guard is what would surface the "stale wsClient from previous session"
            // hypothesis. If a logout's disconnect() left wsClient non-null, every
            // subsequent login no-ops here and the WS handshake never happens — which is
            // exactly the second-login symptom we're tracking down.
            Logger.w(tag = "AuthLifecycle") {
                "AuthCC: connect() SHORT-CIRCUITED — wsClient[${wsClient!!.instanceId}] already exists"
            }
            return
        }

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
                    Logger.i(tag = "AuthLifecycle") {
                        "AuthCC: onConnected fired for ${wsClient?.let { "WS[${it.instanceId}]" } ?: "null-wsClient"}"
                    }
                    _connectionState.update { it.copy(isConnected = true) }
                    logLifecycleSnapshot("onConnected")
                    scope.launch {
                        try {
                            // start() must be called on every (re)connect — not just the first —
                            // because pause() sets isRunning=false on disconnect, and syncAll()
                            // would silently skip if isRunning is still false.
                            driveSyncManager.start()

                            // Reverts block #2 of commit 18483c4e. The WS push path is
                            // engineered to bypass QueryBatch in steady state, so the
                            // server's "auto-process inbox on QueryBatch" doesn't fire
                            // for backlog accumulated while we were offline. Poke each
                            // subscribed drive explicitly so syncAll() below sees the
                            // freshly-processed items. Remove once the server-side
                            // auto-process behaviour is fixed.
                            wsClient?.processAllInboxes()

                            driveSyncManager.syncAll()
                            Logger.i(tag = "AuthLifecycle") { "AuthCC: onConnected post-sync done" }
                        } catch (e: Exception) {
                            Logger.e(throwable = e, tag = "AuthLifecycle") { "AuthCC: syncAll() failed on connect" }
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
                    Logger.i(tag = "AuthLifecycle") {
                        "AuthCC: onDisconnected fired for ${wsClient?.let { "WS[${it.instanceId}]" } ?: "null-wsClient"}"
                    }
                    outboxSync.setOnline(false)
                    _connectionState.update { it.copy(isConnected = false, isConnecting = false) }
                    driveSyncManager.pause()
                    logLifecycleSnapshot("onDisconnected")
                },
                onConnectError = { e ->
                    Logger.w(throwable = e, tag = "AuthLifecycle") {
                        "AuthCC: onConnectError for ${wsClient?.let { "WS[${it.instanceId}]" } ?: "null-wsClient"}: ${e.message}"
                    }
                    outboxSync.setOnline(false)
                    _connectionState.update { it.copy(isConnected = false, isConnecting = false) }
                    logLifecycleSnapshot("onConnectError")
                }
            ).also {
                Logger.i(tag = "AuthLifecycle") { "AuthCC: WS[${it.instanceId}] created, start() invoked" }
                it.start()
            }
    }

    fun setForeground(foreground: Boolean) {
        wsClient?.isInForeground = foreground
    }

    /**
     * Activate an optional add-on drive. When [persist] is true (default), appends the
     * drive to the registry list file on the Chat drive so the activation syncs to the
     * user's other devices. Hot-mounts the drive in [DriveSyncManager] (HTTP polling
     * starts immediately) and schedules a debounced WebSocket reconnect so real-time
     * push arrives within [REFRESH_DEBOUNCE_MS]. Multiple rapid calls coalesce.
     *
     * Pass `persist = false` when the activation originated elsewhere (e.g. another
     * device already wrote the registry update, and the Chat-drive observer surfaced
     * it locally) — persisting again would just churn the file with the same content.
     */
    suspend fun mountDrive(drive: LabeledDrive, persist: Boolean = true) {
        if (persist) driveRegistry.addDrive(drive)
        val newlyMounted = driveSyncManager.mountDrive(drive.drive.alias, drive.label)
        if (!newlyMounted) {
            // Redundant mount — drive was already in DSM. Skipping the refresh
            // trigger is critical: refreshWsSubscription.trigger() would close
            // an in-flight WebSocket connection 500ms later, which is exactly
            // the regression that broke the post-login sync when VaultViewModel
            // reactively re-mounted a drive that AuthCC had already mounted at
            // login time. Logged loud so we can spot redundant callers.
            Logger.w(tag = "AuthLifecycle") {
                "AuthCC: mountDrive(${drive.drive.alias}, ${drive.label}) — already mounted, " +
                    "skipping WS-refresh trigger"
            }
            return
        }
        refreshWsSubscription.trigger()
    }

    /**
     * Deactivate an optional add-on drive. When [persist] is true (default), removes the
     * drive from the registry list file on the Chat drive so the change syncs to the
     * user's other devices. Unmounts from [DriveSyncManager] and schedules a debounced
     * WebSocket reconnect.
     *
     * Intended for user-initiated removals and for the Chat-drive observer's unmount
     * callback (which passes `persist = false` because the change originated elsewhere).
     * The 403/PermissionDenied auto-unmount in [DriveSyncManager] bypasses this path on
     * purpose — re-subscribing would just get rejected again, and we do NOT want to
     * propagate a permission-denied condition as a registry list mutation to other devices.
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
        Logger.i(tag = "AuthLifecycle") {
            "AuthCC: disconnect() begin (wsClient=${wsClient?.let { "WS[${it.instanceId}]" } ?: "null"})"
        }
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
        Logger.i(tag = "AuthLifecycle") { "AuthCC: disconnect() end (wsClient nulled)" }
        logLifecycleSnapshot("disconnect end")
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
