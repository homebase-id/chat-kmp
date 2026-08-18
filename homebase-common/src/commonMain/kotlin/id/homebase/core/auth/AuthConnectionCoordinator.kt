package id.homebase.core.auth

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.peer.PeerWebSocketManager
import id.homebase.api.client.websockets.OdinWebSocketClient
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.diagnostics.BgTrace
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.youauth.DrivePermission
import id.homebase.api.youauth.SecurityContextProvider
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.api.youauth.YouAuthState
import id.homebase.core.config.LabeledDrive
import id.homebase.core.config.mandatorySyncDrives
import id.homebase.core.sync.DriveRegistry
import id.homebase.core.avatars.AppConnectionStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
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
    private val securityContextProvider: SecurityContextProvider,
    private val peerWebSocketManager: PeerWebSocketManager,
    private val onPostAuthenticated: () -> Unit = {},
    /**
     * Active location-tracking profile label for the #1109 background-transition line, or null when
     * unknown/none. Injected as a lambda (wired in AppModule to LocationTrackingCoordinator) so this
     * auth layer stays decoupled from the location module. Default `{ null }` keeps it optional.
     */
    private val locationProfileLabel: () -> String? = { null },
    /**
     * Whether this platform has a push + background-worker fallback for sync while backgrounded
     * (FCM/APNs → WorkManager/BGTask HTTP sync) — wired in AppModule to
     * [PlatformInfo.supportsBackgroundWake] (true on Android/iOS, false on Desktop/Web). When true,
     * the notify WS is closed while backgrounded (#1108) and reconnected on foreground; when false
     * (no push path) the WS is kept connected regardless of foreground state. Default false = keep
     * the WS (fail safe for a platform that forgets to wire this).
     */
    private val backgroundSyncViaPush: Boolean = false,
    /**
     * Initial value of [headless]. True only on platforms that can cold-wake the
     * process in the background (see [PlatformInfo.supportsBackgroundWake]); those
     * defer foreground-only work until [promoteToForeground]. Defaults to false so
     * a platform that forgets to wire [promoteToForeground] fails open (connects on
     * Authenticated) rather than fail closed (hangs on "syncing").
     */
    startsHeadless: Boolean = false,
) {
    // SupervisorJob so one child's failure doesn't cancel its siblings, plus a
    // top-level handler so an *uncaught* exception in any child (notably a transient
    // SQLITE_BUSY in a DB-writing coroutine) is logged instead of escaping to the
    // platform's uncaught handler and cancelling this whole scope. The WebSocket
    // client runs on this same scope; a plain-Job scope previously meant one such
    // throw tore down the reconnect loop and left the app permanently offline.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Logger.e(throwable = e, tag = "AuthLifecycle") {
                "AuthCC: uncaught exception in coordinator scope (isolated, not fatal): ${e.message}"
            }
        }
    )
    private var wsClient: OdinWebSocketClient? = null

    // #1109 background-transition tracking: the last foreground/background state we logged and when,
    // so setForeground() can emit the duration of the window that just ended. Seeded foreground=true
    // at process start (the app opens in the foreground); lastTransitionAtMs anchors the first window.
    private var currentForeground: Boolean = true
    private var lastTransitionAtMs: Long = UnixTimeUtc().milliseconds

    // Peer (owner-hosted) drives mounted this session, alias -> (owner, drive). Lets [unmountDrive]
    // tear down the right per-owner peer websocket given only a driveId. Guarded by [peerOwnersMutex]
    // because mounts arrive from both the Authenticated branch and the registry observer coroutine.
    private val peerDriveOwners = mutableMapOf<Uuid, Pair<OdinId, TargetDrive>>()
    private val peerOwnersMutex = kotlinx.coroutines.sync.Mutex()

    // Serializes the compound WS lifecycle transitions that close+rebuild [wsClient] — the #1108
    // background close/reconnect ([applyWsHold]) and the drive-subscription reconnect
    // ([reconnectWebSocket]) — so a foreground/background toggle can't interleave a drive-mount
    // reconnect and leave two clients or a half-torn-down one. connect() is called WHILE holding
    // this lock and must never take it itself (it doesn't) to avoid re-entrant deadlock.
    private val wsLifecycleMutex = kotlinx.coroutines.sync.Mutex()

    // Coalesces bursts of mountDrive/unmountDrive calls into a single WebSocket reconnect.
    // [OdinWebSocketClient] freezes its drive-subscription list at construction time, so we
    // have to close and reopen the socket to pick up a registry change mid-session. If a user
    // activates two add-ons back-to-back, we want one reconnect, not two.
    private val refreshWsSubscription = DebouncedAction(scope, REFRESH_DEBOUNCE_MS) {
        reconnectWebSocket()
    }

    private val _connectionState = MutableStateFlow(AuthConnectionState())
    val connectionState: StateFlow<AuthConnectionState> = _connectionState.asStateFlow()

    // region Headless mode — FCM-cold-wake suppression of foreground-only work.
    //
    // On Android (and iOS) the process is woken for FCM data messages before
    // the user is looking at the UI. Application init + Koin spin-up means
    // [onAuthStateChanged] still fires Authenticated, but for a headless
    // wake-up we should NOT open the WebSocket, run [onPostAuthenticated]'s
    // UI preload, start the registry observer, or load the profile.
    // [BackgroundSyncOrchestrator.syncIfAuthenticated] only needs the
    // drive mounts and registry bootstrap to do its QueryBatch.
    //
    // [headless] starts true ONLY on background-wake-capable platforms
    // (Android/iOS — see [startsHeadless] / [PlatformInfo.supportsBackgroundWake]);
    // Desktop and Web start false and connect on Authenticated directly. On the
    // headless platforms, the first foreground entry point
    // ([MainActivity.onCreate] on Android, `MainViewController()` on iOS) calls
    // [promoteToForeground], which flips the flag and replays the deferred work if
    // Authenticated already fired during the headless window. If Authenticated
    // hasn't fired yet, the next Authenticated observes !headless and runs the full
    // sequence.
    @Volatile private var headless: Boolean = startsHeadless

    /**
     * Captured drives from the Authenticated branch's bootstrap step, so
     * [promoteToForeground] can replay [connect]/[driveRegistry.start] with
     * the same drive list the headless Authenticated branch already
     * mounted. Null until the first Authenticated lands.
     */
    @Volatile private var lastAuthenticatedDrives: List<LabeledDrive>? = null

    /**
     * Gates [onPostAuthenticated] to exactly once per authenticated session. The headless
     * session-restore race could otherwise DROP it: the foreground call (below) is skipped
     * while headless, and [promoteToForeground] bails when it fires mid-bootstrap with
     * [lastAuthenticatedDrives] still null — so VaultStream/StickerStream/etc. are never
     * started and their screens spin forever. All call sites go through
     * [runPostAuthenticatedOnce]; reset on Unauthenticated so the next login re-runs it.
     */
    @Volatile private var postAuthenticatedRan = false
    private val postAuthGate = kotlinx.coroutines.sync.Mutex()

    /**
     * Normalized (lowercased, hyphen-stripped) ids of the drives this app token can READ,
     * resolved from the security context on each Authenticated transition. Null when unknown
     * (security-context fetch failed or timed out) — in that case [retainGrantedDrives] does not
     * filter, so a transient fetch failure degrades to the pre-existing behaviour rather than
     * hiding drives.
     */
    @Volatile private var grantedDriveIds: Set<String>? = null

    // Background grant-refresh + prune (#1079); cancel-and-relaunched on foreground promotion.
    private var grantReconcileJob: Job? = null
    // Background registry reconcile — the server half of the deferred bootstrap.
    private var registryReconcileJob: Job? = null
    // endregion

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
                // Foreground-only UI preload — see kdoc on [headless]. Runs FIRST in
                // foreground mode (matches pre-headless-mode ordering: preload starts
                // local-DB warmups while drive mount + WS handshake happen in
                // parallel). Deferred to [promoteToForeground] in headless mode.
                val startedHeadless = headless
                if (!startedHeadless) {
                    runPostAuthenticatedOnce()
                    // Before the network work below: load() emits a local-only session first,
                    // and own-vs-peer rendering blocks on that emission.
                    loadProfile()
                }

                // Mount mandatory drives FIRST — before bootstrap, before any network I/O.
                // Encodes the invariant that this app's sync engine always syncs these
                // drives; nothing about the WS handshake or the registry should be able to
                // prevent them from appearing in driveStatuses.
                // Unconditional: BG sync's syncAll() needs the drives mounted.
                driveSyncManager.ensureMandatoryMounted()

                // Resolve the cross-device registry from the local index and never block on
                // the server here — everything below (WS connect, profile load) used to queue
                // behind that round-trip on every login, warm restores included.
                // [scheduleRegistryReconcile] does the server half afterwards; whoever needs
                // the authoritative set waits for it via [awaitRegistryReconcile].
                //
                // Do NOT reintroduce a `headless`-style condition here to decide whether to
                // block: on Android `headless` is true for an ordinary launcher launch too
                // (startsHeadless = supportsBackgroundWake) and is only corrected by
                // promoteToForeground() a few hundred ms later, so it cannot tell a
                // background wake from a user-visible cold start at this point.
                // Unconditional: BG sync's syncAll() needs the drive list.
                val initialDrives = driveRegistry.bootstrap(deferServerReconcile = true)
                // Resolve which optional drives this app token can actually READ before mounting
                // anything. The registry is the cross-device "activated" list and is NOT
                // permission-aware: a drive activated on another device (or before a permission
                // change) can appear here without this app holding a read grant. Mounting such a
                // drive makes the server 403 the REST query-batch AND close the whole notify
                // WebSocket — one unauthorized drive in EstablishConnectionRequest tears down the
                // socket, which surfaced as a dropped initial handshake + premature "connected".
                //
                // The grant fetch does NOT run here anymore (#1079): it's a network call that on a
                // bad network burned ~2.5 min and, upstream of connect()/loadProfile() in this same
                // coroutine, stalled the socket + avatar. Optional drives ~never lose a grant, so we
                // connect immediately with the local registry (retainGrantedDrives is a no-op while
                // grantedDriveIds is null) and reconcile in the background — see
                // [scheduleGrantReconcile], kicked after connect(). The rare "in registry but grant
                // revoked" drive is pruned then (and is also self-healed reactively by the DriveSync
                // 403 unmount + the WS unauthorizedDriveIds drop).

                // Pre-mount the granted optional drives so they're in driveSyncs when
                // onConnected fires start() + syncAll(). mountDrive() defers the network kick
                // while isRunning is false; syncAll() picks them up alongside the mandatory drives.
                // ownerOdinId is set for owner-hosted (peer/community) drives, null for own drives.
                for (drive in initialDrives.retainGrantedDrives()) {
                    driveSyncManager.mountDrive(drive.drive.alias, drive.label, drive.ownerOdinId)
                }
                logLifecycleSnapshot("drives mounted")

                // Stash the FULL registry list: it's the baseline for the registry-change observer
                // (an intentionally-skipped ungranted drive must not look like a brand-new mount)
                // and the replay source for promoteToForeground. connect() re-applies the grant
                // filter for the WebSocket subscription itself.
                lastAuthenticatedDrives = initialDrives

                if (headless) {
                    // Still kick the reconcile: a background wake's syncAll() may be the only
                    // pass a drive activated on another device gets, and BackgroundSyncOrchestrator
                    // awaits this job before syncing.
                    scheduleRegistryReconcile(initialDrives)
                    Logger.i(tag = "AuthLifecycle") {
                        "AuthCC: Authenticated branch — mode=headless " +
                            "deferred=[onPostAuthenticated, connect, driveRegistry.start, loadProfile]"
                    }
                    return
                }

                // Race recovery: we started headless (so the foreground call above was skipped)
                // but promoteToForeground flipped us to foreground mid-bootstrap and bailed
                // because lastAuthenticatedDrives was still null — so its deferred
                // onPostAuthenticated never ran, leaving VaultStream/StickerStream/etc. unstarted
                // (the infinite spinner). We're past the headless return, so run it now;
                // runPostAuthenticatedOnce() is a no-op if the foreground path already ran it.
                runPostAuthenticatedOnce()
                // Guarded, unlike the call above: loadProfile has no once-gate, and a second
                // one would race a failed fetch's fallback over the resolved profile.
                if (startedHeadless) loadProfile()

                // #1108: don't open the notify WS if we authenticated while already backgrounded on a
                // push-capable platform — FCM + WorkManager HTTP cover background sync. setForeground(true)
                // reconnects on the next foreground. (Both this and applyWsHold branch on the same live
                // currentForeground, so they stay consistent without sharing a lock here.)
                if (shouldKeepWebSocketConnected(currentForeground, backgroundSyncViaPush)) {
                    connect(extraDrives = initialDrives)
                } else {
                    Logger.i(tag = "AuthLifecycle") {
                        "AuthCC: Authenticated — backgrounded on push-capable platform, deferring WS connect"
                    }
                }
                // Owner-hosted (peer) drives don't ride the own-host WebSocket — open a per-owner
                // peer websocket for each so live community updates arrive over the owner's host.
                startPeerConnections(initialDrives)
                // Observe cross-device registry changes. We seed the diff baseline with the
                // bootstrap result so the chat-drive sync that later writes the same file
                // into the local index doesn't trigger a spurious onMount for drives that
                // are already mounted.
                driveRegistry.start(
                    onMount = { drive -> mountDrive(drive, persist = false) },
                    onUnmount = { driveId -> unmountDrive(driveId, persist = false) },
                    initialBaseline = initialDrives.mapTo(HashSet()) { it.drive.alias },
                )
                scheduleRegistryReconcile(initialDrives)
                // Resolve read grants off the critical path and prune any live drive we've lost
                // the grant for (rare). Kicked after connect() so the WS refresh has a client.
                scheduleGrantReconcile()
            }
            is YouAuthState.Initializing -> {
                // ignore
            }
            is YouAuthState.Unauthenticated -> {
                disconnect()
                // Reset the post-auth gate so the next login re-runs onPostAuthenticated().
                postAuthGate.withLock { postAuthenticatedRan = false }
                // The profile is loaded here on auth (loadProfile); clear it on the
                // way out so the owner avatar/name doesn't bleed into the login screen
                // or the next identity. AuthCC owns the profile lifecycle, so it clears
                // it directly rather than over the bus.
                ownerSessionRepository.clear()
                // Broadcast session end so every stateful singleton clears its own
                // in-memory caches (conversations, messages, contacts, moments,
                // vault, …). Emitted AFTER disconnect() — WS is closed and DriveSync
                // stopped — so nothing can re-populate after the services reset.
                Logger.i(tag = "AuthLifecycle") { "AuthCC: emitting SessionEnded (logout)" }
                eventBus.tryEmit(BackendEvent.SessionEnded)
            }
            else -> {
                disconnect()
            }
        }
    }

    /**
     * Flip out of headless mode and run any deferred foreground-only work
     * (`onPostAuthenticated`, `connect`, `driveRegistry.start`,
     * `loadProfile`) that the headless [onAuthStateChanged] branch skipped.
     *
     * Called by the first foreground entry point per process —
     * `MainActivity.onCreate` on Android, `MainViewController()` on iOS.
     * Both fire only on actual UI launch (FCM cold-wake doesn't trigger
     * either), which is exactly the signal we want.
     *
     * Idempotent: safe on Activity recreation (config change, return from
     * background) and on multiple iOS view-controller creates. Non-suspend
     * for caller ergonomics — the actual replay runs on the AuthCC scope.
     */
    fun promoteToForeground() {
        if (!headless) {
            Logger.d(tag = "AuthLifecycle") {
                "AuthCC: promoteToForeground() — already foreground, no-op"
            }
            return
        }
        headless = false
        val drives = lastAuthenticatedDrives
        if (drives == null) {
            Logger.i(tag = "AuthLifecycle") {
                "AuthCC: promoteToForeground() — flipped to foreground; auth not yet resolved, " +
                    "deferred work will run on the next Authenticated"
            }
            return
        }
        Logger.i(tag = "AuthLifecycle") {
            "AuthCC: promoteToForeground() — running deferred=[onPostAuthenticated, connect, " +
                "driveRegistry.start, loadProfile]"
        }
        scope.launch {
            runPostAuthenticatedOnce()
            connect(extraDrives = drives)
            startPeerConnections(drives)
            driveRegistry.start(
                onMount = { drive -> mountDrive(drive, persist = false) },
                onUnmount = { driveId -> unmountDrive(driveId, persist = false) },
                initialBaseline = drives.mapTo(HashSet()) { it.drive.alias },
            )
            scheduleRegistryReconcile(drives)
            loadProfile()
            // Retry point: a cold background wake may have missed the grant fetch on a dead
            // network; the first real foreground open re-runs it (cancel-and-relaunch) and prunes.
            scheduleGrantReconcile()
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

    /**
     * Run [onPostAuthenticated] at most once per authenticated session. Called from the
     * foreground branch, [promoteToForeground]'s replay, and the race-recovery path in
     * [onAuthStateChanged]; the gate makes the narrow window where both the Authenticated
     * branch and a mid-bootstrap [promoteToForeground] reach it a single, safe run.
     */
    private suspend fun runPostAuthenticatedOnce() {
        val shouldRun = postAuthGate.withLock {
            if (postAuthenticatedRan) false else { postAuthenticatedRan = true; true }
        }
        if (shouldRun) onPostAuthenticated()
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

        // Filter to drives this app token can read — covers reconnects (extraDrives == null →
        // loadDrives()) and the login/promote paths, so an ungranted drive is never put on the
        // WebSocket subscription where it would make the server close the socket.
        val optionalDrives = (extraDrives ?: driveRegistry.loadDrives()).retainGrantedDrives()
        _connectionState.update { it.copy(isConnecting = true) }
        wsClient =
            OdinWebSocketClient(
                credentialsManager = credentialsManager,
                driveSyncManager = driveSyncManager,
                scope = scope,
                eventBus = eventBus,
                databaseManager = databaseManager,
                // Own-host WebSocket subscribes to own drives only. Peer (owner-hosted) drives get
                // their own per-owner connection via [startPeerConnections] / [PeerWebSocketManager].
                drives = (mandatorySyncDrives + optionalDrives.filter { it.ownerOdinId == null })
                    .map { it.drive },
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
                            // Instrumentation: confirm the post-connect cleanup
                            // actually runs (this is the only place zombie
                            // checked-out outbox rows get revived). A missing
                            // "onConnected → clearCheckout" line in the log means
                            // onConnected never fired (no clean WS handshake).
                            Logger.i(tag = "AuthLifecycle") { "AuthCC: onConnected → clearCheckout() + setOnline(true) + send()" }
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
        // #1109: one consolidated, greppable transition line carrying the duration of the window that
        // just ended plus the active location profile — so a day's log gives a clean fg/bg breakdown
        // (and background-window attribution) from a single `grep BgTrace` instead of hand-stitching.
        if (foreground != currentForeground) {
            val now = UnixTimeUtc().milliseconds
            BgTrace.log(BgTrace.transition(foreground, now - lastTransitionAtMs, locationProfileLabel()))
            currentForeground = foreground
            lastTransitionAtMs = now
        }
        // Keep backoff/ping cadence in sync for the cases where the WS stays open (Desktop/Web, or a
        // foreground transition before applyWsHold reconnects).
        wsClient?.isInForeground = foreground
        // #1108: close the WS when backgrounded on a push-capable platform; reconnect on foreground.
        scope.launch { applyWsHold() }
    }

    /**
     * Idempotent WS "hold" mirroring [LocationTrackingCoordinator]'s GPS hold (#1108): keep the notify
     * WS connected only when [shouldKeepWebSocketConnected] is true, otherwise close it for the
     * background window and rely on FCM push + WorkManager HTTP sync. Reads [currentForeground] LIVE
     * inside the lock, so two out-of-order launches (a rapid bg→fg) still converge on the latest
     * state — the last to run re-reads the shared field.
     */
    private suspend fun applyWsHold() {
        wsLifecycleMutex.withLock {
            val authResolved = lastAuthenticatedDrives != null && !headless
            when (wsHoldDecision(currentForeground, backgroundSyncViaPush, wsClient != null, authResolved)) {
                // Rebuild a WS we tore down for background. connect()'s own wsClient != null guard
                // makes a redundant call a no-op.
                WsHoldAction.CONNECT -> {
                    Logger.i(tag = "AuthLifecycle") { "AuthCC: foregrounded — reconnecting WS" }
                    connect()
                }
                WsHoldAction.PARK -> {
                    val old = wsClient!! // PARK is only returned when a client is present
                    wsClient = null
                    old.close()
                    // close() sets the client's terminal `closed` flag BEFORE cancelling its loop, so
                    // its handleDisconnected() early-returns and the onDisconnected callback never
                    // fires. Mark offline here ourselves — otherwise isOnline stays stale-true and
                    // BackgroundSyncOrchestrator.syncIfAuthenticated() would skip the FCM→HTTP
                    // background sync ("WS online — skipping"), silently breaking background sync.
                    outboxSync.setOnline(false)
                    _connectionState.update { connectionStateAfterWsPark(it) }
                    BgTrace.log(BgTrace.wsPark("backgrounded-push-covered"))
                    Logger.i(tag = "AuthLifecycle") {
                        "AuthCC: WS[${old.instanceId}] closed for background (push-covered)"
                    }
                }
                WsHoldAction.NONE -> {}
            }
        }
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
        val owner = drive.ownerOdinId
        val newlyMounted = driveSyncManager.mountDrive(drive.drive.alias, drive.label, owner)
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
        if (owner != null) {
            // Peer drive: open/extend the per-owner peer websocket instead of reconnecting the
            // own-host socket (the community drive isn't hosted on creds.domain).
            peerOwnersMutex.withLock { peerDriveOwners[drive.drive.alias] = owner to drive.drive }
            peerWebSocketManager.mount(owner, drive.drive)
        } else {
            refreshWsSubscription.trigger()
        }
    }

    /**
     * Mount a community drive hosted on another identity ([ownerOdinId]) directly. Part 1 transport
     * affordance for exercising the validation gate against a real existing community before the
     * Part 2 feature (and its registry entries) exists. Defaults to `persist = false` so it doesn't
     * write the cross-device registry.
     */
    suspend fun mountPeerDrive(
        ownerOdinId: OdinId,
        communityDrive: TargetDrive,
        label: String,
        persist: Boolean = false,
    ) {
        mountDrive(LabeledDrive(communityDrive, label, ownerOdinId), persist = persist)
    }

    /** Open a peer websocket for every owner-hosted drive in [drives]. No-op for own drives. */
    private suspend fun startPeerConnections(drives: List<LabeledDrive>) {
        for (drive in drives) {
            val owner = drive.ownerOdinId ?: continue
            peerOwnersMutex.withLock { peerDriveOwners[drive.drive.alias] = owner to drive.drive }
            peerWebSocketManager.mount(owner, drive.drive)
        }
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
        val peer = peerOwnersMutex.withLock { peerDriveOwners.remove(driveId) }
        if (peer != null) {
            // Peer drive: tear down its per-owner websocket rather than reconnecting the own-host one.
            peerWebSocketManager.unmount(peer.first, peer.second)
        } else {
            refreshWsSubscription.trigger()
        }
    }

    // Close the current WebSocket and open a new one. [connect] reads the DriveRegistry
    // fresh each call, so the new socket's drive subscription reflects the latest mount/
    // unmount state. Called via [refreshWsSubscription] after the debounce window elapses.
    // No-op when [wsClient] is null: logged-out or pre-auth bursts don't need a reconnect —
    // the next organic [connect] call (on login or reconnect) will read the fresh registry.
    private suspend fun reconnectWebSocket() {
        // Serialized with [applyWsHold] so a background close and a drive-subscription reconnect
        // can't interleave. connect() is invoked while holding the lock and must not take it.
        wsLifecycleMutex.withLock {
            val old = wsClient ?: return@withLock
            wsClient = null
            old.close()
            connect()
        }
    }

    private suspend fun disconnect() {
        Logger.i(tag = "AuthLifecycle") {
            "AuthCC: disconnect() begin (wsClient=${wsClient?.let { "WS[${it.instanceId}]" } ?: "null"})"
        }
        // Before driveRegistry.stop() clears the baseline this job diffs against.
        registryReconcileJob?.cancel()
        registryReconcileJob = null
        // Before refreshWsSubscription.cancel(): its prune path calls trigger(), which would re-arm
        // the debouncer — and unmount drives — against a session already being torn down (#1237).
        grantReconcileJob?.cancel()
        grantReconcileJob = null
        refreshWsSubscription.cancel()
        driveRegistry.stop()
        // Close every per-owner peer websocket so a second login doesn't inherit the first user's
        // community connections.
        peerWebSocketManager.reset()
        peerOwnersMutex.withLock { peerDriveOwners.clear() }
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

    /**
     * Refresh [grantedDriveIds] from the security context. Runs in the **background** (via
     * [scheduleGrantReconcile]), off the connect/loadProfile critical path — the security-context
     * GET (`/auth/context`) has no app-level retry and on a failing network burns its whole
     * HttpTimeout budget (~2.5 min under OkHttp DNS-retry), which used to stall the socket + avatar
     * (#1079). On failure it leaves the set null (prune nothing — degrade to the local registry).
     */
    private suspend fun refreshGrantedDriveIds() {
        val ctx = securityContextProvider.getSecurityContext()
        if (ctx == null) {
            Logger.w(tag = "AuthLifecycle") {
                "AuthCC: security context unavailable — drive-grant filter disabled this cycle"
            }
            grantedDriveIds = null
            return
        }
        val readable = ctx.permissionContext.permissionGroups
            .flatMap { it.driveGrants ?: emptyList() }
            .filter { (it.permissionedDrive.permission.sumOf { p -> p.value } and DrivePermission.Read.value) != 0 }
            .mapTo(mutableSetOf()) { normalizeDriveId(it.permissionedDrive.drive.alias) }
        grantedDriveIds = readable
        Logger.i(tag = "AuthLifecycle") { "AuthCC: readable drive grants resolved (count=${readable.size})" }
    }

    /**
     * Resolve read grants in the background and prune any *live* optional drive we've lost the
     * grant for. Never gates startup. Cancel-and-relaunch so a foreground reopen after a
     * dead-network cold start retries with a fresh fetch. Pruning uses [unmountDrive] with
     * `persist = false` — it stops DriveSync + rebuilds the WS subscription filtered, but leaves the
     * persisted DriveRegistry intact (the drive re-mounts on a later startup if the grant returns).
     */
    private fun scheduleGrantReconcile() {
        grantReconcileJob?.cancel()
        grantReconcileJob = scope.launch {
            refreshGrantedDriveIds()
            val granted = grantedDriveIds ?: return@launch // grants unknown → prune nothing
            val mandatory = mandatorySyncDrives.mapTo(HashSet()) { it.drive.alias }
            val toPrune = drivesToPrune(driveSyncManager.driveStatuses.value.keys, mandatory, granted)
            for (driveId in toPrune) {
                Logger.w(tag = "AuthLifecycle") {
                    "AuthCC: read grant revoked — pruning drive $driveId from the live session"
                }
                unmountDrive(driveId, persist = false)
            }
        }
    }

    /**
     * The server half of the deferred [DriveRegistry.bootstrap]: hot-mount anything the
     * server registry lists that [known] doesn't — a drive activated on the user's other
     * device. Uses the same `persist = false` path as the cross-device registry observer,
     * so a discovery here is indistinguishable downstream from one the chat-drive sync made.
     *
     * Discovered drives are appended to [lastAuthenticatedDrives] because
     * [promoteToForeground] replays `connect()` from it — without that, a drive found during
     * a headless wake would be mounted but missing from the WebSocket subscription.
     *
     * Cancelled in [disconnect] so a reconcile still in flight at logout can't mount drives
     * into a dead session.
     */
    private fun scheduleRegistryReconcile(known: List<LabeledDrive>) {
        val knownIds = known.mapTo(HashSet()) { it.drive.alias }
        registryReconcileJob?.cancel()
        registryReconcileJob = scope.launch {
            driveRegistry.reconcileWithServer(knownIds) { drive ->
                lastAuthenticatedDrives = (lastAuthenticatedDrives ?: emptyList()) + drive
                mountDrive(drive, persist = false)
            }
        }
    }

    /**
     * Wait for the in-flight [scheduleRegistryReconcile] to finish, so a caller that genuinely
     * needs the server-authoritative drive set gets it.
     *
     * This is the seam that replaced blocking the whole connect sequence on the registry fetch.
     * The distinction "is a user waiting on this?" cannot be made inside [onAuthStateChanged] —
     * on Android [headless] is true for an ordinary launcher launch as well as an FCM wake, and
     * is only corrected once [promoteToForeground] runs. The one component that knows
     * unambiguously is the background sync itself, so it asks rather than being told.
     */
    suspend fun awaitRegistryReconcile() {
        registryReconcileJob?.join()
    }

    /**
     * Drop drives this app token has no read grant for (see [grantedDriveIds]). No-op when the
     * grant set is unknown. Applied to optional/registry drives only — mandatory drives are always
     * required and are never filtered here.
     */
    private fun List<LabeledDrive>.retainGrantedDrives(): List<LabeledDrive> {
        val granted = grantedDriveIds ?: return this
        return filter { ld ->
            val ok = normalizeDriveId(ld.drive.alias.toString()) in granted
            if (!ok) {
                Logger.w(tag = "AuthLifecycle") {
                    "AuthCC: skipping drive '${ld.label}' (${ld.drive.alias}) — app token has no read " +
                        "grant; not mounting/subscribing it (avoids server WS close + REST 403)"
                }
            }
            ok
        }
    }


    companion object {
        private const val REFRESH_DEBOUNCE_MS = 500L
    }
}

// Match compareStringUuId's normalization so drive ids compare regardless of hyphen/case format.
internal fun normalizeDriveId(driveId: String): String = driveId.lowercase().replace("-", "")

/**
 * The live optional drives to prune because their read grant was revoked (#1079): [active] drives
 * (from DriveSyncManager.driveStatuses) that are not mandatory and not in the [granted] set.
 * Mandatory drives (chat/contacts/profile) are never pruned.
 */
internal fun drivesToPrune(active: Set<Uuid>, mandatory: Set<Uuid>, granted: Set<String>): List<Uuid> =
    active.filter { it !in mandatory && normalizeDriveId(it.toString()) !in granted }

/**
 * Whether the notify WebSocket should be kept connected (#1108). Keep it while [foreground]; and on
 * platforms WITHOUT a push fallback ([backgroundSyncViaPush] == false, i.e. Desktop/Web) keep it
 * regardless. Suppress it only when backgrounded on a push-capable platform (Android/iOS), where FCM
 * push + WorkManager HTTP sync cover background work. Pure so the policy is unit-tested directly.
 */
internal fun shouldKeepWebSocketConnected(foreground: Boolean, backgroundSyncViaPush: Boolean): Boolean =
    foreground || !backgroundSyncViaPush

internal enum class WsHoldAction { CONNECT, PARK, NONE }

/**
 * The action [AuthConnectionCoordinator.applyWsHold] should take (#1108), given the desired state.
 * [wsPresent] = a live wsClient currently exists; [authResolved] = auth has resolved this session and
 * we're out of headless mode (so a rebuild is legitimate). CONNECT rebuilds a WS we tore down for
 * background; PARK closes the live WS for the background window; NONE leaves things as they are. Pure
 * so the control flow — including "don't connect before auth resolves" and "don't park a WS that's
 * already gone" — is unit-tested directly.
 */
internal fun wsHoldDecision(
    foreground: Boolean,
    backgroundSyncViaPush: Boolean,
    wsPresent: Boolean,
    authResolved: Boolean,
): WsHoldAction = when {
    shouldKeepWebSocketConnected(foreground, backgroundSyncViaPush) ->
        if (!wsPresent && authResolved) WsHoldAction.CONNECT else WsHoldAction.NONE
    wsPresent -> WsHoldAction.PARK
    else -> WsHoldAction.NONE
}

/**
 * The connection state after the WS is parked for background (#1108): offline and not connecting.
 * Extracted so a test locks the invariant that a park marks us offline — required because
 * OdinWebSocketClient.close() never fires onDisconnected, so without this the state would stay
 * stale-connected and BackgroundSyncOrchestrator would skip the FCM→HTTP background sync.
 */
internal fun connectionStateAfterWsPark(current: AuthConnectionState): AuthConnectionState =
    current.copy(isConnected = false, isConnecting = false)

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
