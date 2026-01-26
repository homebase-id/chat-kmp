package id.homebase.api.client.websockets

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.crypto.AesCbc
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.toBase64
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.SharedSecretEncryptedPayload
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64


/**
 * WebSocket client for connecting to Odin notify/ws endpoint
 */
class OdinWebSocketClient(
    private val credentialsManager: CredentialsManager,
    private val scope: CoroutineScope,
    private val eventBus: EventBus,
    private val databaseManager: DatabaseManager,
    private val drives: List<TargetDrive>,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: () -> Unit = {}
) {

    private var reconnectDelayMs = 1_000L
    private val MAX_RECONNECT_DELAY_MS = 5_000L

    private val client = HttpClient {
        install(WebSockets)
    }

    private var fileHeaderProcessor = MainIndexMetaHelpers.HomebaseFileProcessor(databaseManager)

    private lateinit var sharedSecret: ByteArray

    private val _connectionState = MutableStateFlow<WebSocketState>(WebSocketState.Disconnected)
    val connectionState: StateFlow<WebSocketState> = _connectionState.asStateFlow()

    private var connectionJob: Job? = null
    private var session: DefaultClientWebSocketSession? = null


    private val pingSupervisor = WebSocketPingSupervisor(
        scope = scope,
        sessionProvider = { session },
        encrypt = { encryptData(it) },
        onOnline = { handleGoingOnline() },
        onOffline = { handleDisconnected() }
    )

    private suspend fun handleDisconnected() {
        eventBus.emit(BackendEvent.ConnectionOffline)
        onDisconnected()
    }

    private suspend fun handleGoingOnline() {
        eventBus.emit(BackendEvent.ConnectionOnline)
    }

    fun start() {
        if (connectionJob?.isActive == true) return

        connectionJob = scope.launch {
            while (true) {
                eventBus.emit(BackendEvent.Connecting)

                try {
                    connectOnce()

                    // If connectOnce returns normally, we consider that a success
                    // Reset backoff so next failure retries fast again
                    reconnectDelayMs = 1_000L

                } catch (e: Exception) {
                    Logger.e(e) { "WebSocket connect failed" }
                }

                eventBus.emit(BackendEvent.ConnectionOffline)

                Logger.w {
                    "WebSocket disconnected, retrying in ${reconnectDelayMs}ms"
                }

                delay(withJitter(reconnectDelayMs))

                reconnectDelayMs =
                    (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            }

        }
    }

    private fun withJitter(delayMs: Long): Long {
        val jitter = (delayMs * 0.2).toLong() // ±20%
        return delayMs + (-jitter..jitter).random()
    }

    private suspend fun connectOnce() {
        val creds = credentialsManager.getActiveCredentials()
            ?: run {
                Logger.w { "No active credentials, cannot connect WebSocket" }
                return
            }

        val identity = creds.domain
        sharedSecret = creds.sharedSecret.unsafeBytes

        _connectionState.value = WebSocketState.Connecting

        val wsUrl = "wss://${identity}/api/apps/v1/notify/ws"
        Logger.i { "Connecting to WebSocket at $wsUrl" }

        val appCookieName = "BX0900"

        client.webSocket(
            urlString = wsUrl,
            request = {
                headers.append("Cookie", "$appCookieName=${creds.clientAccessToken}")
            }
        ) {
            session = this
            _connectionState.value = WebSocketState.Connected

            establishConnectionRequest()

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
                session = null // Clear session reference
                if (_connectionState.value != WebSocketState.Error("Unknown error")) {
                    _connectionState.value = WebSocketState.Disconnected
                }
                handleDisconnected()
                Logger.i { "WebSocket connection ended" }
            }
        }

        session = null
        pingSupervisor.stop()
        _connectionState.value = WebSocketState.Disconnected
        handleDisconnected()
    }

    private suspend fun handleTextFrame(frame: Frame.Text) {
        try {
            val text = frame.readText()

            val decryptedJson = decryptData(text)
            val notification =
                OdinSystemSerializer.deserialize<ClientNotificationPayload>(decryptedJson)

            handleNotification(notification)

        } catch (e: Exception) {
            Logger.e(e) { "Error handling WebSocket message" }
        }
    }

    private suspend fun handleNotification(notification: ClientNotificationPayload) {
//        Logger.i("Handling notification type ${notification.notificationType}")
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

            ClientNotificationType.fileAdded -> {
                handleFileEvent(notification)
            }

            ClientNotificationType.fileDeleted -> {
            }

            ClientNotificationType.fileModified -> {
                handleFileEvent(notification)
            }

            ClientNotificationType.connectionRequestReceived -> {
            }

            ClientNotificationType.deviceConnected -> {
            }

            ClientNotificationType.deviceDisconnected -> {
            }

            ClientNotificationType.connectionRequestAccepted -> {
            }

            ClientNotificationType.inboxItemReceived -> {
                handleProcessInbox(notification)
            }

            ClientNotificationType.newFollower -> {
            }

            ClientNotificationType.statisticsChanged -> {
            }

            ClientNotificationType.reactionContentAdded -> {
            }

            ClientNotificationType.reactionContentDeleted -> {
            }

            ClientNotificationType.allReactionsByFileDeleted -> {
            }

            ClientNotificationType.appNotificationAdded -> {
            }

            ClientNotificationType.introductionsReceived -> {
            }

            ClientNotificationType.introductionAccepted -> {
            }

            ClientNotificationType.connectionFinalized -> {
            }

            ClientNotificationType.error -> {
                Logger.e("Notification of type error was sent.")
            }

            else -> {
            }
        }
    }

    private suspend fun handleProcessInbox(
        notification: ClientNotificationPayload
    ) {
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


    private suspend fun handleFileEvent(notification: ClientNotificationPayload) {

        var theFileNotification =
            OdinSystemSerializer.deserialize<ClientDriveNotification>(notification.data)
        val theFile = theFileNotification.header!!
        val lastModified = theFile.fileMetadata.updated

        val files = listOf(theFile.asHomebaseFile(SecureByteArray(sharedSecret)))

        val identityId = credentialsManager.getActiveCredentials()!!.getIdentityId()

        try {
            fileHeaderProcessor.baseUpsertEntryZapZap(
                identityId = identityId,
                driveId = theFileNotification.targetDrive!!.alias,
                fileHeaders = files,
                cursor = null
            )
        } catch (e: Exception) {
            Logger.e("DB upsert failed for batch: ${e.message}")
        }

        eventBus.emit(
            BackendEvent.DriveEvent.BatchReceived(
                theFile.driveId,
                1,
                1,
                lastModified,
                files
            )
        )

        eventBus.emit(
            BackendEvent.DriveEvent.Completed(
                theFile.driveId,
                1
            )
        )
    }

    private suspend fun handleAuthError(notification: ClientNotificationPayload) {
        var message = notification.data
        Logger.e("Authentication Error was sent from web socket. [$message]")
        eventBus.emit(BackendEvent.ConnectionOffline)
    }

    private suspend fun onHandshakeSuccess() {
        Logger.i { "Device handshake successful" }
        pingSupervisor.notifySessionReconnected()
        pingSupervisor.start()

        onConnected()

        eventBus.emit(BackendEvent.ConnectionOnline)

    }


    /**
     *
     * Disconnect from the WebSocket
     */
    fun disconnect() {
        pingSupervisor.stop()
        session = null
        connectionJob?.cancel()
        connectionJob = null
        _connectionState.value = WebSocketState.Disconnected
        Logger.i { "WebSocket disconnected" }
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

        val envelope = OdinSystemSerializer.deserialize<WebSocketClientNotificationPayload>(text)
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
        notify(
            command = "establishConnectionRequest",
            payload = EstablishConnectionRequest(
                drives = drives
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
     * Close the client and release resources
     */
    fun close() {
        disconnect()
        client.close()
    }
}
