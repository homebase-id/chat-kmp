package id.homebase.api.client.websockets

import co.touchlab.kermit.Logger
import id.homebase.api.client.SharedSecretEncryptedPayload
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.Base64UrlEncoder
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.DriveWebSocketUpsertWorker
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.toBase64
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * WebSocket client for connecting to Odin notify/ws endpoint
 */
class OdinWebSocketClient(
    private val credentialsManager: CredentialsManager,
    private val driveSyncManager: DriveSyncManager,
    private val scope: CoroutineScope,
    private val eventBus: EventBus,
    private val databaseManager: DatabaseManager,
    private val drives: List<TargetDrive>,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {},
    private val onConnectError: (Throwable) -> Unit = {},
) {
    /**
     * Monotonic id assigned at construction. Lets the AuthLifecycle log narrative match
     * up "WS[N] created", "WS[N] start()", "WS[N] handshake successful", "WS[N] close()",
     * "WS[N] disconnected" across a single OdinWebSocketClient instance — and notice when
     * a stale instance from a previous session is being torn down.
     */
    val instanceId: Long = nextInstanceId.incrementAndGet()

    init {
        Logger.i(tag = "WebSocket") { "WS[$instanceId] ctor (drives=${drives.size})" }
    }

    private var reconnectDelayMs = 1_000L
    private val MAX_RECONNECT_DELAY_MS = 5_000L
    private val MAX_RECONNECT_DELAY_BACKGROUND_MS = 30_000L
    private var closed = false

    private val client = HttpClient {
        // TODO: enable per-message deflate compression via WebSocketDeflateExtension once
        //  server support is confirmed (RFC 7692 / permessage-deflate).
        //  install(WebSockets) { extensions { install(WebSocketDeflateExtension) } }
        install(WebSockets)
    }

    private var fileHeaderProcessor = MainIndexMetaHelpers.HomebaseFileProcessor(databaseManager)

    // Per-drive WS upsert workers. Lazily created on the first file
    // event for a drive (see [getOrCreateWorker]). Each worker
    // batches incoming files into one DB transaction and emits a
    // single [BackendEvent.DataEvent.BatchReceived] per drain;
    // shape mirrors [DriveSync].
    //
    // Cleared in [disconnect]. Mount/unmount of drives is handled
    // automatically because [AuthConnectionCoordinator.reconnectWebSocket]
    // destroys the old [OdinWebSocketClient] and creates a fresh one
    // with an empty map.
    private val wsUpsertWorkers = mutableMapOf<Uuid, DriveWebSocketUpsertWorker>()
    private val wsUpsertWorkersMutex = Mutex()

    private lateinit var sharedSecret: ByteArray

    private val _connectionState = MutableStateFlow<WebSocketState>(WebSocketState.Disconnected)
    val connectionState: StateFlow<WebSocketState> = _connectionState.asStateFlow()

    private var connectionJob: Job? = null
    private var reconnectDelayJob: Job? = null
    private var session: DefaultClientWebSocketSession? = null

    @Volatile
    var isInForeground: Boolean = true
        set(value) {
            val previous = field
            field = value
            pingSupervisor.isInForeground = value
            if (!previous && value) wakeForReconnect()
        }

    @Volatile
    private var handshakeDone = false

    private val unauthorizedDriveAliases = mutableSetOf<Uuid>()

    private val notificationBuffer =
        mutableListOf<ClientNotificationPayload>()

    private val notificationBufferMutex = Mutex()

    private var notificationFlushJob: Job? = null

    private val NOTIFICATION_BURST_MS = 200L


    private val pingSupervisor = WebSocketPingSupervisor(
        scope = scope,
        sessionProvider = { session },
        encrypt = { encryptData(it) },
        onOnline = { handleGoingOnline() },
        onOffline = { handleDisconnected() }
    )

    private suspend fun handleDisconnected() {
        if (closed) return

        eventBus.emit(BackendEvent.ConnectionOffline)
        onDisconnected()

        connectionJob?.cancel()
        start()
    }

    private suspend fun handleGoingOnline() {
        eventBus.emit(BackendEvent.ConnectionOnline)
    }

    fun start() {
        val active = connectionJob?.isActive == true
        Logger.i(tag = "WebSocket") { "WS[$instanceId] start() (jobActive=$active)" }
        if (active) return

        connectionJob = scope.launch {
            // Logged immediately on dispatch so we can tell from logs whether
            // the connection loop ever actually ran. If "loop dispatched" never
            // appears after a "start()" log line, the coroutine was cancelled
            // before reaching its body (e.g. a refreshWsSubscription-driven
            // close() racing with construction).
            Logger.i(tag = "WebSocket") { "WS[$instanceId] connection loop dispatched" }
            while (true) {
                // tryEmit, not emit. The WS lifecycle is the source of truth for
                // connection state (see [connectionState]); the bus emission is an
                // informational mirror for collectors that care. A blocking emit
                // here parks the entire WS connect on whatever the slowest
                // unrelated subscriber is doing — which is what locked up the
                // post-login sync when a singleton's eventBus collector got stuck
                // mid-batch during the logout DB wipe. tryEmit drops the event
                // (returns false) when the buffer is full rather than suspend.
                eventBus.tryEmit(BackendEvent.Connecting)

                try {
                    val connected = connectOnce()

                    // Only reset backoff if we actually established a connection.
                    // Early returns (e.g. no credentials) should not reset backoff.
                    if (connected) {
                        reconnectDelayMs = 1_000L
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onConnectError(e)
                    Logger.e(throwable = e, tag = "WebSocket") {
                        "WS[$instanceId] connect loop caught exception: ${e.message}"
                    }
                }

                eventBus.tryEmit(BackendEvent.ConnectionOffline)

                Logger.w(tag = "WebSocket") { "WS[$instanceId] disconnected, retrying in ${reconnectDelayMs}ms" }

                // The sleep is launched as a child of the connection job so
                // wakeForReconnect() can cancel just this delay (e.g. when the
                // app comes to the foreground) and we iterate immediately
                // instead of waiting out the backoff cap. Cancelling the
                // parent connectionJob still cascades to this child as usual.
                val sleepJob = launch { delay(withJitter(reconnectDelayMs)) }
                reconnectDelayJob = sleepJob
                try {
                    sleepJob.join()
                } finally {
                    reconnectDelayJob = null
                }
                Logger.i { "Delay completed, reconnecting..." }

                val maxDelay = if (isInForeground) MAX_RECONNECT_DELAY_MS else MAX_RECONNECT_DELAY_BACKGROUND_MS
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(maxDelay)
            }

        }
    }

    /**
     * Reset the reconnect backoff to the initial delay and cancel any
     * pending reconnect-sleep so the loop iterates immediately. Safe to
     * call from any thread; no-op when the client is closed or already
     * connected. Intended for foreground transitions and other "we want
     * a fresh attempt now" signals.
     */
    fun wakeForReconnect() {
        if (closed) return
        reconnectDelayMs = 1_000L
        reconnectDelayJob?.cancel()
    }

    private fun withJitter(delayMs: Long): Long {
        val jitter = (delayMs * 0.2).toLong() // ±20%
        return delayMs + (-jitter..jitter).random()
    }

    private suspend fun connectOnce(): Boolean {
        val creds = credentialsManager.getActiveCredentials()
            ?: run {
                Logger.w(tag = "WebSocket") { "WS[$instanceId] No active credentials, cannot connect" }
                return false
            }

        val identity = creds.domain
        sharedSecret = creds.sharedSecret.unsafeBytes

        // Per-drive WS upsert workers materialise lazily in
        // [getOrCreateWorker] when a file event for that drive
        // arrives — no need to predeclare which drives we expect.

        _connectionState.value = WebSocketState.Connecting

        val wsUrl = "wss://${identity}/api/v2/notify/ws-token"
        Logger.i(tag = "WebSocket") { "WS[$instanceId] Connecting to WebSocket at $wsUrl" }

        // Auth for the v2 ws-token route is carried on the WebSocket upgrade itself
        // via Sec-WebSocket-Protocol — no cookie, no Authorization header. We send
        // two subprotocol values: the application protocol (which the server must
        // echo back in the 101) and a bearer credential of the form
        // odin.bearer.<base64url(33-byte App ClientAuthToken)>.
        val bearer = "odin.bearer.${creds.clientAccessToken}"

        client.webSocket(
            urlString = wsUrl,
            request = {
                // Two appends, not one comma-joined string. The browser WebSocket
                // constructor (Ktor JS/wasm engine) validates each subprotocol
                // value against the RFC 7230 token grammar; a single
                // "odin.notify.v1, $bearer" value fails because the space after
                // the comma is not a valid tchar, throwing SyntaxError before
                // any network I/O. The JVM/Darwin/OkHttp engines tolerate the
                // joined form by writing it raw to the wire — appending each
                // value separately works on every engine.
                headers.append(HttpHeaders.SecWebSocketProtocol, "odin.notify.v1")
                headers.append(HttpHeaders.SecWebSocketProtocol, bearer)
            }
        ) {
            session = this
            _connectionState.value = WebSocketState.Connected

            // Verify the server committed to our application subprotocol on the 101.
            // The log line is deliberately verbose on the first connect so we can
            // confirm the engine passed our comma-separated header through intact
            // and the server negotiated the expected value.
            val negotiated = call.response.headers[HttpHeaders.SecWebSocketProtocol]
            Logger.i(tag = "WebSocket") {
                "WS[$instanceId] negotiated Sec-WebSocket-Protocol=$negotiated"
            }
            if (negotiated?.trim() != "odin.notify.v1") {
                Logger.w(tag = "WebSocket") {
                    "WS[$instanceId] server did not commit to odin.notify.v1 (got=$negotiated) — closing"
                }
                close()
                return@webSocket
            }

            handshakeDone = false
            establishConnectionRequest()

            val handshakeTimeoutJob = scope.launch {
                delay(10_000L)
                if (!handshakeDone) {
                    Logger.w { "Handshake timeout — closing session to force reconnect" }
                    session?.close()
                }
            }

            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> handleTextFrame(frame)
                        is Frame.Close -> {
                            Logger.i { "WebSocket closed by server" }
                            break
                        }

                        else -> {
                            // no op
                            Logger.d { "Received other frame type: ${frame.frameType}" }
                        }
                    }
                }
            } finally {
                handshakeTimeoutJob.cancel()
                session = null // Clear session reference
                pingSupervisor.stop()
                if (_connectionState.value != WebSocketState.Error("Unknown error")) {
                    _connectionState.value = WebSocketState.Disconnected
                }
                handleDisconnected()
                Logger.i { "WebSocket connection ended" }
            }
        }
        return true
    }

    private suspend fun handleTextFrame(frame: Frame.Text) {
        try {
            val text = frame.readText()

            val decryptedJson = decryptData(text)
            val notification: ClientNotificationPayload? = OdinSystemSerializer
                .deserialize<ClientNotificationPayload>(decryptedJson)

            if (notification == null) {
                Logger.e { "Received null WebSocket notification payload, ignoring" }
                return
            }

            handleNotification(notification)

        } catch (e: Exception) {
            Logger.e(e) { "Error handling WebSocket message" }
        }
    }

    private suspend fun handleNotification(notification: ClientNotificationPayload) {
        notificationBufferMutex.withLock {
            notificationBuffer += notification
        }

        // cancel pending flush
        notificationFlushJob?.cancel()

        notificationFlushJob = scope.launch {
            delay(NOTIFICATION_BURST_MS)

            val batch = notificationBufferMutex.withLock {
                val snapshot = notificationBuffer.toList()
                notificationBuffer.clear()
                snapshot
            }

            for (n in batch) {
                try {
                    dispatchNotification(n)
                } catch (e: Exception) {
                    Logger.e(e) { "Failed to dispatch notification type=${n.notificationType}, data=${n.data.take(200)}" }
                }
            }
        }
    }

    private suspend fun dispatchNotification(notification: ClientNotificationPayload) {
        when (notification.notificationType) {
            ClientNotificationType.deviceHandshakeSuccess -> {
                onHandshakeSuccess()
            }

            ClientNotificationType.pong -> {
                pingSupervisor.notifyPongReceived()
            }

            ClientNotificationType.authenticationError -> {
                handleAuthError(notification)
            }

            ClientNotificationType.inboxItemReceived -> {
                // Re-enabled (was a no-op since the server-side "auto-process
                // on QueryBatch" promise covered the previous use cases).
                // Confirmed regression for the moments drive: a peer-posted
                // comment/moment arrives at the receiver as
                // `inboxItemReceived` but no `fileAdded` follows — the file
                // stays in inbox limbo and the detail/feed UI never updates.
                // The explicit per-item processInbox ack moves the inbox
                // entry onto the drive, which then fires `fileAdded` →
                // `BatchReceived` → service/VM/UI as normal.
                //
                // TODO: remove once the server reliably auto-processes the
                // inbox on transit arrival without needing the client to ack.
                handleProcessInbox(notification)
            }

            ClientNotificationType.fileAdded -> {
                handleFileEvent(notification)
            }

            ClientNotificationType.fileDeleted -> {
                handleFileEvent(notification)
            }

            ClientNotificationType.fileModified -> {
                handleFileEvent(notification)
            }

            ClientNotificationType.statisticsChanged -> {
                handleFileEvent(notification)
            }

            ClientNotificationType.reactionContentAdded -> {
                handleReactionEvent(notification, false)
            }

            ClientNotificationType.reactionContentDeleted -> {
                handleReactionEvent(notification, true)
            }

            ClientNotificationType.allReactionsByFileDeleted -> {
                handleAllReactionsDeletedEvent(notification)
            }

            ClientNotificationType.introductionsReceived -> {
                val d = OdinSystemSerializer.deserialize<IntroductionReceivedNotification>(
                    notification.data
                )
                eventBus.emit(
                    BackendEvent.CircleNetworkEvent.IntroductionsReceived(
                        introducerOdinId = d.introducerOdinId,
                        introduction = d.introduction
                    )
                )
            }

            ClientNotificationType.introductionAccepted -> {
                val d = OdinSystemSerializer.deserialize<IntroductionAcceptedNotification>(
                    notification.data
                )

                eventBus.emit(
                    BackendEvent.CircleNetworkEvent.IntroductionAccepted(
                        introducerOdinId = d.introducerOdinId,
                        recipient = d.recipient
                    )
                )
            }

            ClientNotificationType.connectionRequestReceived -> {
                val d = OdinSystemSerializer.deserialize<ConnectionRequestReceivedNotification>(
                    notification.data
                )

                eventBus.emit(
                    BackendEvent.CircleNetworkEvent.ConnectionRequestReceived(
                        sender = d.sender
                    )
                )
            }

            ClientNotificationType.connectionRequestAccepted -> {
                val d = OdinSystemSerializer.deserialize<ConnectionRequestAcceptedNotification>(
                    notification.data
                )

                eventBus.emit(
                    BackendEvent.CircleNetworkEvent.ConnectionRequestAccepted(
                        acceptedBy = d.sender
                    )
                )
            }

            ClientNotificationType.connectionFinalized -> {

                val d = OdinSystemSerializer.deserialize<ConnectionRequestFinalizedNotification>(
                    notification.data
                )

                eventBus.emit(
                    BackendEvent.CircleNetworkEvent.ConnectionRequestFinalized(
                        identity = d.identity
                    )
                )
            }

            ClientNotificationType.newFollower -> {
                val d = OdinSystemSerializer.deserialize<NewFollowerNotification>(
                    notification.data
                )

                eventBus.emit(
                    BackendEvent.CircleNetworkEvent.NewFollower(
                        identity = d.sender
                    )
                )
            }


            ClientNotificationType.appNotificationAdded -> {
            }


            ClientNotificationType.deviceConnected -> {
                // just means another device was connected
            }

            ClientNotificationType.deviceDisconnected -> {
                // just means another device was disconnected
            }

            ClientNotificationType.error -> {
                Logger.e("Notification of type error was sent.")
            }

            else -> {
            }
        }
    }

    /**
     * Per-item inbox ack — tells the server to process the just-arrived
     * inbox entry for [notification]'s `targetDrive` and write it onto the
     * drive. The server then fires `fileAdded` (or `fileModified` for an
     * upsert), which lands at [handleFileEvent] and propagates as a normal
     * `BatchReceived` event.
     *
     * Originally added for the same reason as [processAllInboxes] (cold
     * reconnect backlog), then disabled in favor of relying on the server's
     * "auto-process on QueryBatch" path. That path covers the chat drive
     * because the conversation screen does its own paging QueryBatches, but
     * for the moments drive (no ongoing polling once cold-load completes)
     * the inbox-arrived item never gets processed. Re-enabling closes that
     * gap. TODO: remove once the server auto-processes inbox arrivals.
     */
    private suspend fun handleProcessInbox(notification: ClientNotificationPayload) {
        val n =
            OdinSystemSerializer.deserialize<InboxItemReceivedNotification>(
                notification.data
            )
        notify(
            command = "processInbox",
            payload = ProcessInboxPayload(
                targetDrive = n.targetDrive,
                batchSize = 100
            )
        )
    }

/**
     * Reaction add/remove notification — no-op for every drive.
     *
     * The server fires a parallel `statisticsChanged` notification
     * carrying the updated file header (with the new
     * `reactionPreview`); that notification lands in
     * [handleFileEvent] → the per-drive pure-push worker, which
     * writes the new header to DriveMainIndex. Calling syncDrive
     * here would refetch the same data via HTTP. The per-user
     * reaction details (who reacted with which emoji, used by the
     * reaction-detail view) are fetched on-demand via
     * `getReactions()` and don't ride on this WS event at all.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun handleReactionEvent(
        notification: ClientNotificationPayload,
        isDeleted: Boolean,
    ) {
        // intentional no-op — see KDoc
    }

    /**
     * All-reactions-cleared notification — no-op for every drive,
     * same rationale as [handleReactionEvent]: the parallel
     * `statisticsChanged` for the file rewrites `reactionPreview` to
     * empty via the pure-push worker.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun handleAllReactionsDeletedEvent(notification: ClientNotificationPayload) {
        // intentional no-op — see KDoc
    }

    /**
     * Get-or-create the WS upsert worker for [driveId]. Returns null
     * when the client is closed or has no active credentials —
     * caller falls through to [DriveSyncManager.syncDrive].
     */
    private suspend fun getOrCreateWorker(driveId: Uuid): DriveWebSocketUpsertWorker? {
        if (closed) return null
        val identityId = credentialsManager.getActiveCredentials()?.getIdentityId() ?: return null
        return wsUpsertWorkersMutex.withLock {
            wsUpsertWorkers[driveId] ?: DriveWebSocketUpsertWorker(
                identityId = identityId,
                driveId = driveId,
                databaseManager = databaseManager,
                eventBus = eventBus,
                scope = scope,
            ).also { wsUpsertWorkers[driveId] = it }
        }
    }

    /**
     * Dispatcher for `fileAdded` / `fileDeleted` / `fileModified` /
     * `statisticsChanged` notifications, for any drive.
     *
     * If the WS payload carries a header, decrypt it and submit to
     * the per-drive [DriveWebSocketUpsertWorker] — no HTTP
     * round-trip. The worker batches bursts of incoming files into
     * one DB transaction and emits a single
     * `BatchReceived(source = WebSocket)` event.
     *
     * Falls back to [DriveSyncManager.syncDrive] when:
     *  - the notification has no header (e.g. some
     *    `statisticsChanged` variants),
     *  - decrypt or upsert throws,
     *  - we can't get/create a worker (closed, no credentials).
     *
     * Every fallback is logged at INFO so the rate is observable in
     * production — a steady stream for any drive means the WS
     * payload is missing headers somewhere we didn't expect.
     */
    private suspend fun handleFileEvent(notification: ClientNotificationPayload) {
        val fileNotification =
            OdinSystemSerializer.deserialize<ClientDriveNotification>(notification.data)
        val driveId = fileNotification.targetDrive!!.alias
        val header = fileNotification.header

        if (header != null) {
            val worker = getOrCreateWorker(driveId)
            if (worker != null) {
                try {
                    val file = header.asHomebaseFile(SecureByteArray(sharedSecret))
                    worker.submit(file)
                    return
                } catch (e: Exception) {
                    Logger.w(e) {
                        "WSPush: pure-push path failed for drive=$driveId " +
                            "(notificationType=${notification.notificationType}); " +
                            "falling back to syncDrive: ${e.message}"
                    }
                    // fall through
                }
            }
        }

        Logger.i(tag = "WebSocket") {
            "WSFileEvent: syncDrive($driveId) fallback — " +
                "notificationType=${notification.notificationType} " +
                "headerPresent=${header != null}"
        }
        try {
            driveSyncManager.syncDrive(driveId)
        } catch (e: Exception) {
            Logger.e("handleFileEvent() probably used invalid driveId $driveId Exception:$e")
        }
    }

    // Auth errors are per-drive, not per-connection. We intentionally do NOT emit
    // ConnectionOffline here — doing so would cascade through onDisconnected →
    // driveSyncManager.pause() and kill syncing for ALL drives, even authorized ones.
    //
    // Instead we track the rejected drive alias so establishConnectionRequest() can
    // exclude it on the next (re)connect. On the first attempt the server may still
    // close the connection after the error; in that case the normal reconnect loop
    // fires and the second attempt succeeds without the rejected drive (~1 s delay).
    // The set resets when a new OdinWebSocketClient is created (i.e. on re-auth).
    //
    // We emit DriveAuthorizationFailed so the UI can offer the user a chance to
    // extend permissions via the owner console.
    private suspend fun handleAuthError(notification: ClientNotificationPayload) {
        val message = notification.data
        Logger.w("WebSocket auth error (non-fatal): [$message]")

        // Parse drive alias from server message format:
        // "Unauthorized to read to drive [<uuid>]"
        val uuidRegex = Regex("\\[([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})]")
        val match = uuidRegex.find(message)
        if (match != null) {
            val alias = runCatching { Uuid.parse(match.groupValues[1]) }.getOrNull()
            if (alias != null) {
                unauthorizedDriveAliases.add(alias)
                Logger.w("Drive $alias excluded from future WebSocket subscriptions")
            }
        }

        eventBus.emit(BackendEvent.DriveAuthorizationFailed(message))
    }

    private suspend fun onHandshakeSuccess() {
        Logger.i(tag = "WebSocket") { "WS[$instanceId] handshake successful" }
        handshakeDone = true
        pingSupervisor.notifySessionReconnected()
        pingSupervisor.start()
        onConnected()
        eventBus.emit(BackendEvent.ConnectionOnline)

        // Catch-up after a (re)connect is handled by
        // [AuthConnectionCoordinator]'s `onConnected` callback, which
        // fires `driveSyncManager.syncAll()` on every handshake — see
        // `AuthConnectionCoordinator.kt:179-202`. That covers all
        // mounted drives, which is what we need now that per-event
        // file notifications take the pure-push path for every drive.
    }

    /**
     *
     * Disconnect from the WebSocket
     */
    fun disconnect() {
        closed = true
        pingSupervisor.stop()
        session = null
        connectionJob?.cancel()
        connectionJob = null
        // Snapshot-then-clear is safe without acquiring [wsUpsertWorkersMutex]:
        // [getOrCreateWorker] checks `closed` before allocating, so any
        // concurrent call after `closed = true` returns null and never
        // re-populates the map. Worst case is a worker created right
        // before `closed = true` flipped — that one ends up in the
        // snapshot and gets cancelled.
        val workersSnapshot = wsUpsertWorkers.values.toList()
        wsUpsertWorkers.clear()
        workersSnapshot.forEach { it.cancel() }
        _connectionState.value = WebSocketState.Disconnected
        Logger.i(tag = "WebSocket") { "WS[$instanceId] disconnected" }
    }

    /**
     * Encrypts data with the shared secret
     */
    private suspend fun encryptData(message: WebsocketCommand): SharedSecretEncryptedPayload {
        val iv = ByteArrayUtil.getRndByteArray(16)
        val json = OdinSystemSerializer.serialize(message);
        val encryptedBytes = AesCbc.encrypt(json.encodeToByteArray(), sharedSecret, iv)

        // Build and return the payload
        return SharedSecretEncryptedPayload(
            iv = iv.toBase64(),
            data = encryptedBytes.toBase64()
        )
    }

    private suspend fun decryptData(
        text: String
    ): String {

        val envelope =
            OdinSystemSerializer.deserialize<WebSocketClientNotificationPayload>(text)
        if (!envelope.isEncrypted) {
            return envelope.payload
        }

        val encryptedPayload =
            OdinSystemSerializer.deserialize<SharedSecretEncryptedPayload>(envelope.payload)

        val iv = Base64.decode(encryptedPayload.iv)
        val encryptedData = Base64.decode(encryptedPayload.data)
        val decryptedBytes = AesCbc.decrypt(encryptedData, sharedSecret, iv)

        return decryptedBytes.decodeToString()
    }

    /**
     * Send EstablishConnectionRequest to server
     */
    suspend fun establishConnectionRequest() {
        val activeDrives = drives.filter { it.alias !in unauthorizedDriveAliases }
        if (activeDrives.isEmpty()) {
            Logger.e("No authorized drives for WebSocket subscription")
            return
        }
        notify(
            command = "establishConnectionRequest",
            payload = EstablishConnectionRequest(
                drives = activeDrives
            )
        )
    }

    private suspend inline fun <reified T> notify(
        command: String,
        payload: T
    ) {
        val currentSession = session
        if (currentSession == null) {
            Logger.w { "Cannot send $command: WebSocket not connected" }
            return
        }

        try {
            val message = WebsocketCommand(
                command = command,
                data = OdinSystemSerializer.serialize(payload)
            )

            val encryptedMessage = encryptData(message)
            val jsonMessage = OdinSystemSerializer.serialize(encryptedMessage)

            currentSession.send(Frame.Text(jsonMessage))

            Logger.d { "Sent WebSocket command: $command" }

        } catch (e: Exception) {
            Logger.e(e) { "Failed to send WebSocket command: $command" }
        }
    }

    /**
     * Send processInbox for every subscribed drive so the server moves any
     * queued inbox items to the drive before the next QueryBatch sync.
     * Call this right after a successful handshake / reconnect.
     */
    suspend fun processAllInboxes() {
        // Reverts block #2 of commit 18483c4e — see AuthConnectionCoordinator
        // for the rationale. Called on every (re)connect to flush any inbox
        // backlog accumulated while we were offline, before syncAll() runs.
        for (drive in drives) {
            notify(
                command = "processInbox",
                payload = ProcessInboxPayload(
                    targetDrive = drive,
                    batchSize = 100
                )
            )
        }
    }

    /**
     * Close the client and release resources
     */
    fun close() {
        Logger.i(tag = "WebSocket") { "WS[$instanceId] close()" }
        disconnect()
        client.close()
    }

    companion object {
        // Process-lifetime counter for instanceId. Starts at 1 on first construction.
        private val nextInstanceId = atomic(0L)
    }
}
