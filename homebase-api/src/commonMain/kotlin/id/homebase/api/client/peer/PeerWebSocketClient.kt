package id.homebase.api.client.peer

import co.touchlab.kermit.Logger
import id.homebase.api.client.SharedSecretEncryptedPayload
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.websockets.ClientDriveNotification
import id.homebase.api.client.websockets.ClientNotificationPayload
import id.homebase.api.client.websockets.ClientNotificationType
import id.homebase.api.client.websockets.EstablishConnectionRequest
import id.homebase.api.client.websockets.InboxItemReceivedNotification
import id.homebase.api.client.websockets.ProcessInboxPayload
import id.homebase.api.client.websockets.WebSocketClientNotificationPayload
import id.homebase.api.client.websockets.WebSocketPingSupervisor
import id.homebase.api.client.websockets.WebSocketState
import id.homebase.api.client.websockets.WebsocketCommand
import id.homebase.api.common.OdinId
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.DriveWebSocketUpsertWorker
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.toBase64
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
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
import kotlinx.serialization.Serializable

/** Establish envelope for a peer websocket — carries the guest client-auth token alongside the
 *  shared-secret-encrypted command. Mirrors the JS `EstablishConnectionOverPeer` wrapper. */
@Serializable
private data class PeerEstablishEnvelope(
    val sharedSecretEncryptedOptions: SharedSecretEncryptedPayload,
    val clientAuthToken64: String,
)

/**
 * A websocket bound to a **peer's** host (a community owner) — the live counterpart to the
 * over-peer query/upload broker paths. One instance per owner host. Mirrors the JS
 * `WebsocketProviderOverPeer.ts`:
 *
 *  1. exchange a peer token via [PeerNotificationProvider.getPeerToken] (auth token + shared secret),
 *  2. connect directly to `wss://$ownerOdinId/.../notify/peer/ws` with the token,
 *  3. `establishConnectionRequest` for the community [drives],
 *  4. decrypt incoming `fileAdded/fileModified/fileDeleted/statisticsChanged` headers with the PEER
 *     shared secret and feed them to a per-drive [DriveWebSocketUpsertWorker] keyed by the LOCAL
 *     user's identity → `BatchReceived` → the same Stream/Service pipeline own drives use.
 *
 * Reuses [DriveWebSocketUpsertWorker] and [WebSocketPingSupervisor] wholesale; only the host,
 * auth, and establish-envelope differ from [id.homebase.api.client.websockets.OdinWebSocketClient].
 */
class PeerWebSocketClient(
    private val ownerOdinId: OdinId,
    private val drives: List<TargetDrive>,
    private val credentialsManager: CredentialsManager,
    private val peerNotificationProvider: PeerNotificationProvider,
    private val scope: CoroutineScope,
    private val eventBus: EventBus,
    private val databaseManager: DatabaseManager,
) {
    private var reconnectDelayMs = 1_000L
    private val maxReconnectDelayMs = 30_000L
    private var closed = false

    private val client = HttpClient { install(WebSockets) }

    // Per-drive upsert workers, keyed by drive alias. Lazily created on the first file event.
    private val wsUpsertWorkers = mutableMapOf<Uuid, DriveWebSocketUpsertWorker>()
    private val wsUpsertWorkersMutex = Mutex()

    // Peer auth material (refetched on authenticationError / reconnect). null until first connect.
    private var authToken64: String? = null
    private var peerSharedSecret: ByteArray? = null

    private val _connectionState = MutableStateFlow<WebSocketState>(WebSocketState.Disconnected)
    val connectionState: StateFlow<WebSocketState> = _connectionState.asStateFlow()

    private var connectionJob: Job? = null
    private var session: DefaultClientWebSocketSession? = null

    @Volatile private var handshakeDone = false

    private val pingSupervisor = WebSocketPingSupervisor(
        scope = scope,
        sessionProvider = { session },
        encrypt = { encryptCommand(it) },
        onOnline = { },
        onOffline = { handleDisconnected() },
    )

    fun start() {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            while (!closed) {
                try {
                    connectOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(throwable = e, tag = TAG) {
                        "peer WS[$ownerOdinId] connect failed: ${e.message}"
                    }
                }
                if (closed) break
                Logger.w(tag = TAG) { "peer WS[$ownerOdinId] disconnected, retry in ${reconnectDelayMs}ms" }
                delay(reconnectDelayMs)
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(maxReconnectDelayMs)
            }
        }
    }

    private suspend fun handleDisconnected() {
        if (closed) return
        eventBus.emit(BackendEvent.ConnectionOffline)
        connectionJob?.cancel()
        start()
    }

    private suspend fun connectOnce() {
        // Peer token (cached on the instance; refetched after an authentication error clears it).
        if (authToken64 == null || peerSharedSecret == null) {
            val token = peerNotificationProvider.getPeerToken(ownerOdinId) ?: run {
                Logger.w(tag = TAG) { "peer WS[$ownerOdinId] preauth failed — no token" }
                return
            }
            authToken64 = token.authenticationToken64
            peerSharedSecret = Base64.decode(token.sharedSecret)
        }
        val token64 = authToken64 ?: return
        val ss = peerSharedSecret ?: return

        _connectionState.value = WebSocketState.Connecting
        val wsUrl = "wss://$ownerOdinId$PEER_WS_PATH"
        Logger.i(tag = TAG) { "peer WS[$ownerOdinId] connecting to $wsUrl (drives=${drives.size})" }

        client.webSocket(
            urlString = wsUrl,
            request = {
                // Guest client-auth token carried in the SUB32 header (JS parity). The exact v2 peer
                // WS auth scheme must be confirmed against the server (see plan "Open items").
                headers.append("SUB32", token64)
            },
        ) {
            session = this
            _connectionState.value = WebSocketState.Connected
            handshakeDone = false
            establishConnectionRequest(ss, token64)

            val handshakeTimeoutJob = scope.launch {
                delay(10_000L)
                if (!handshakeDone) {
                    Logger.w(tag = TAG) { "peer WS[$ownerOdinId] handshake timeout — closing" }
                    session?.close()
                }
            }

            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> handleTextFrame(frame, ss)
                        is Frame.Close -> break
                        else -> Unit
                    }
                }
            } finally {
                handshakeTimeoutJob.cancel()
                session = null
                pingSupervisor.stop()
                if (_connectionState.value !is WebSocketState.Error) {
                    _connectionState.value = WebSocketState.Disconnected
                }
            }
        }
    }

    private suspend fun handleTextFrame(frame: Frame.Text, ss: ByteArray) {
        try {
            val decryptedJson = decryptData(frame.readText(), ss)
            val notification = OdinSystemSerializer
                .deserialize<ClientNotificationPayload>(decryptedJson) ?: return
            dispatchNotification(notification, ss)
        } catch (e: Exception) {
            Logger.e(e) { "peer WS[$ownerOdinId] error handling frame" }
        }
    }

    private suspend fun dispatchNotification(notification: ClientNotificationPayload, ss: ByteArray) {
        when (notification.notificationType) {
            ClientNotificationType.deviceHandshakeSuccess -> {
                Logger.i(tag = TAG) { "peer WS[$ownerOdinId] handshake success" }
                handshakeDone = true
                reconnectDelayMs = 1_000L
                pingSupervisor.notifySessionReconnected()
                pingSupervisor.start()
                eventBus.emit(BackendEvent.ConnectionOnline)
            }
            ClientNotificationType.pong -> pingSupervisor.notifyPongReceived()
            ClientNotificationType.authenticationError -> {
                Logger.w(tag = TAG) { "peer WS[$ownerOdinId] auth error — invalidating token" }
                // Drop the cached token so the next connect re-fetches it, then force a reconnect.
                authToken64 = null
                peerSharedSecret = null
                session?.close()
            }
            ClientNotificationType.inboxItemReceived -> handleProcessInbox(notification)
            ClientNotificationType.fileAdded,
            ClientNotificationType.fileDeleted,
            ClientNotificationType.fileModified,
            ClientNotificationType.statisticsChanged -> handleFileEvent(notification, ss)
            else -> Unit
        }
    }

    private suspend fun handleFileEvent(notification: ClientNotificationPayload, ss: ByteArray) {
        val fileNotification =
            OdinSystemSerializer.deserialize<ClientDriveNotification>(notification.data)
        val driveId = fileNotification.targetDrive?.alias ?: return
        val header = fileNotification.header ?: run {
            Logger.i(tag = TAG) {
                "peer WS[$ownerOdinId] file event drive=$driveId with no header " +
                    "(type=${notification.notificationType}) — next syncAll will catch up"
            }
            return
        }

        val worker = getOrCreateWorker(driveId) ?: return
        try {
            val file = header.asHomebaseFile(SecureByteArray(ss))
            worker.submit(file)
        } catch (e: Exception) {
            Logger.w(e) {
                "peer WS[$ownerOdinId] pure-push failed drive=$driveId — next syncAll will catch up"
            }
        }
    }

    private suspend fun handleProcessInbox(notification: ClientNotificationPayload) {
        val n = OdinSystemSerializer.deserialize<InboxItemReceivedNotification>(notification.data)
        notifyCommand("processInbox", ProcessInboxPayload(targetDrive = n.targetDrive, batchSize = 100))
    }

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

    private suspend fun establishConnectionRequest(ss: ByteArray, token64: String) {
        val command = WebsocketCommand(
            command = "establishConnectionRequest",
            data = OdinSystemSerializer.serialize(EstablishConnectionRequest(drives = drives)),
        )
        val envelope = PeerEstablishEnvelope(
            sharedSecretEncryptedOptions = encryptCommand(command, ss),
            clientAuthToken64 = token64,
        )
        session?.send(Frame.Text(OdinSystemSerializer.serialize(envelope)))
    }

    private suspend fun notifyCommand(command: String, payload: Any) {
        val ss = peerSharedSecret ?: return
        val cmd = WebsocketCommand(command = command, data = OdinSystemSerializer.serialize(payload))
        val encrypted = encryptCommand(cmd, ss)
        session?.send(Frame.Text(OdinSystemSerializer.serialize(encrypted)))
    }

    /** Encrypt a command with the peer shared secret; used by the ping supervisor. */
    private suspend fun encryptCommand(command: WebsocketCommand): SharedSecretEncryptedPayload =
        encryptCommand(command, peerSharedSecret ?: ByteArray(0))

    private suspend fun encryptCommand(command: WebsocketCommand, ss: ByteArray): SharedSecretEncryptedPayload {
        val iv = ByteArrayUtil.getRndByteArray(16)
        val json = OdinSystemSerializer.serialize(command)
        val encrypted = AesCbc.encrypt(json.encodeToByteArray(), ss, iv)
        return SharedSecretEncryptedPayload(iv = iv.toBase64(), data = encrypted.toBase64())
    }

    private suspend fun decryptData(text: String, ss: ByteArray): String {
        val envelope = OdinSystemSerializer.deserialize<WebSocketClientNotificationPayload>(text)
        if (!envelope.isEncrypted) return envelope.payload
        val encryptedPayload =
            OdinSystemSerializer.deserialize<SharedSecretEncryptedPayload>(envelope.payload)
        val iv = Base64.decode(encryptedPayload.iv)
        val encryptedData = Base64.decode(encryptedPayload.data)
        return AesCbc.decrypt(encryptedData, ss, iv).decodeToString()
    }

    /** Close this peer connection and release all per-drive workers. */
    fun close() {
        Logger.i(tag = TAG) { "peer WS[$ownerOdinId] close()" }
        closed = true
        pingSupervisor.stop()
        session = null
        connectionJob?.cancel()
        connectionJob = null
        val workers = wsUpsertWorkers.values.toList()
        wsUpsertWorkers.clear()
        workers.forEach { it.cancel() }
        _connectionState.value = WebSocketState.Disconnected
        client.close()
    }

    companion object {
        private const val TAG = "PeerWebSocket"

        // Direct peer-WS route on the owner's host (V2 backend spec).
        // NOTE: the V2 backend authenticates this socket IN-BAND via the first
        // `SocketAuthenticationPackage` message, NOT the SUB32 header + establish-wrapper model this
        // client currently sends. The path is correct; the handshake auth still needs to be reworked
        // to send the SocketAuthenticationPackage before live updates will connect.
        private const val PEER_WS_PATH = "/api/v2/peer/notify/ws-token"
    }
}
