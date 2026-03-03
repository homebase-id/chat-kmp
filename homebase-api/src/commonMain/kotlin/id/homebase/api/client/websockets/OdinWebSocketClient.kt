package id.homebase.api.client.websockets

import co.touchlab.kermit.Logger
import id.homebase.api.client.SharedSecretEncryptedPayload
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.TargetDrive
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.SecureByteArray
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.DriveSyncManager
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.toBase64
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
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
    private val driveSyncManager: DriveSyncManager,
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

    private val notificationBuffer =
        mutableListOf<ClientNotificationPayload>()

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
        eventBus.emit(BackendEvent.ConnectionOffline)
        onDisconnected()

        connectionJob?.cancel()
        start()
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(e) { "WebSocket connect failed ${e.message}" }
                }

                eventBus.emit(BackendEvent.ConnectionOffline)

                Logger.w { "WebSocket disconnected, retrying in ${reconnectDelayMs}ms" }

                delay(withJitter(reconnectDelayMs))
                Logger.i { "Delay completed, reconnecting..." }

                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
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
            val notification = OdinSystemSerializer
                .deserialize<ClientNotificationPayload>(decryptedJson)

            handleNotification(notification)

        } catch (e: Exception) {
            Logger.e(e) { "Error handling WebSocket message" }
        }
    }

    private suspend fun handleNotification(notification: ClientNotificationPayload) {
        notificationBuffer += notification

        // cancel pending flush
        notificationFlushJob?.cancel()

        notificationFlushJob = scope.launch {
            delay(NOTIFICATION_BURST_MS)

            val batch = notificationBuffer.toList()
            notificationBuffer.clear()

            for (n in batch) {
                dispatchNotification(n)
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

    private suspend fun handleReactionEvent(
        notification: ClientNotificationPayload,
        isDeleted: Boolean
    ) {
        val eventData = OdinSystemSerializer
            .deserialize<ClientReactionNotification>(notification.data)
        val driveId = eventData.fileId.driveId
        driveSyncManager.syncDrive(driveId)

    }

    private suspend fun handleAllReactionsDeletedEvent(notification: ClientNotificationPayload) {
        val file =
            OdinSystemSerializer.deserialize<InternalDriveFileId>(notification.data)

        try {
            driveSyncManager.syncDrive(file.driveId)
        } catch (e: Exception) {
            Logger.e("handleAllReactionsDeletedEvent() probably used invalid driveId ${file.driveId} Exception:$e")
        }
    }


    private suspend fun handleFileEvent(notification: ClientNotificationPayload) {
        val fileNotification =
            OdinSystemSerializer.deserialize<ClientDriveNotification>(notification.data)
        val driveId = fileNotification.targetDrive!!.alias

        try {
            driveSyncManager.syncDrive(driveId)
        } catch (e: Exception) {
            Logger.e("handleFileEvent() probably used invalid driveId $driveId Exception:$e")
        }
    }


    private suspend fun handleFileEvent_manual(notification: ClientNotificationPayload) {
        val fileNotification =
            OdinSystemSerializer.deserialize<ClientDriveNotification>(notification.data)

        val header = fileNotification.header!!
        val driveId = fileNotification.targetDrive!!.alias
        val identityId = credentialsManager.getActiveCredentials()!!.getIdentityId()

        val file = header.asHomebaseFile(SecureByteArray(sharedSecret))
        val lastModified = file.fileMetadata.updated

        val batch = listOf(file)
        try {
            fileHeaderProcessor.baseUpsertEntryZapZap(
                identityId = identityId,
                driveId = driveId,
                fileHeaders = batch,
                cursor = null
            )
        } catch (e: Exception) {
            Logger.e("DB upsert failed for burst: ${e.message}")
        }

        eventBus.emit(
            BackendEvent.DriveEvent.BatchReceived(
                driveId = driveId,
                totalCount = batch.size,
                batchCount = batch.size,
                latestModified = lastModified,
                batchData = batch,
                source = BackendEvent.SyncSource.WebSocket
            )
        )

        Logger.i {
            "Flushed ${batch.size} file events for drive $driveId"
        }
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
