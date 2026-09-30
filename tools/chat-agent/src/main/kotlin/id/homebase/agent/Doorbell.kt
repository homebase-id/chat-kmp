package id.homebase.agent

import id.homebase.api.client.HttpClientProvider
import id.homebase.api.client.SharedSecretEncryptedPayload
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.websockets.ClientNotificationPayload
import id.homebase.api.client.websockets.ClientNotificationType
import id.homebase.api.client.websockets.EstablishConnectionRequest
import id.homebase.api.client.websockets.WebSocketClientNotificationPayload
import id.homebase.api.client.websockets.WebSocketPingSupervisor
import id.homebase.api.client.websockets.WebsocketCommand
import id.homebase.api.crypto.AesCbc
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.toBase64
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.io.encoding.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HANDSHAKE_TIMEOUT_MS = 10_000L
private val QUIET = setOf(
    ClientNotificationType.pong,
    ClientNotificationType.deviceHandshakeSuccess,
    ClientNotificationType.deviceConnected,
    ClientNotificationType.deviceDisconnected,
)

fun sessionConnector(session: Session, verbose: Boolean, log: (String) -> Unit): Connector {
    val client = HttpClientProvider.create().config { install(WebSockets) }
    return { onUp, onRing ->
        val creds = session.credentials.getActiveCredentials() ?: error("no credentials")
        val secret = creds.sharedSecret.unsafeBytes
        val bearer = "odin.bearer." + creds.clientAccessToken.replace('+', '-').replace('/', '_').trimEnd('=')

        suspend fun encrypt(command: WebsocketCommand): SharedSecretEncryptedPayload {
            val iv = ByteArrayUtil.getRndByteArray(16)
            val bytes = AesCbc.encrypt(OdinSystemSerializer.serialize(command).encodeToByteArray(), secret, iv)
            return SharedSecretEncryptedPayload(iv = iv.toBase64(), data = bytes.toBase64())
        }

        suspend fun decrypt(text: String): String {
            val envelope = OdinSystemSerializer.deserialize<WebSocketClientNotificationPayload>(text)
            if (!envelope.isEncrypted) return envelope.payload
            val inner = OdinSystemSerializer.deserialize<SharedSecretEncryptedPayload>(envelope.payload)
            return AesCbc.decrypt(Base64.decode(inner.data), secret, Base64.decode(inner.iv)).decodeToString()
        }

        client.webSocket(
            urlString = "wss://${creds.domain}/api/v2/notify/ws-token",
            request = { headers.append(HttpHeaders.SecWebSocketProtocol, "odin.notify.v1, $bearer") },
        ) {
            val socket = this
            val negotiated = call.response.headers[HttpHeaders.SecWebSocketProtocol]?.trim()
            check(negotiated == "odin.notify.v1") { "unexpected subprotocol $negotiated" }

            var dropReason: String? = null
            var up = false
            val ping = WebSocketPingSupervisor(
                scope = this,
                sessionProvider = { socket },
                encrypt = ::encrypt,
                onOnline = {},
                onOffline = {
                    dropReason = "pong timeout"
                    socket.close()
                },
            )
            launch {
                delay(HANDSHAKE_TIMEOUT_MS)
                if (!up) {
                    dropReason = "handshake timeout"
                    socket.close()
                }
            }
            val request = EstablishConnectionRequest(drives = listOf(SystemDriveConstants.chatDrive))
            val establish = WebsocketCommand("establishConnectionRequest", OdinSystemSerializer.serialize(request))
            send(Frame.Text(OdinSystemSerializer.serialize(encrypt(establish))))
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val note = runCatching { OdinSystemSerializer.deserialize<ClientNotificationPayload>(decrypt(frame.readText())) }
                        .getOrNull() ?: continue
                    when (note.notificationType) {
                        ClientNotificationType.deviceHandshakeSuccess -> {
                            up = true
                            ping.notifySessionReconnected()
                            ping.start()
                            onUp()
                        }
                        ClientNotificationType.pong -> ping.notifyPongReceived()
                        ClientNotificationType.authenticationError, ClientNotificationType.error -> {
                            dropReason = "server ${note.notificationType}: ${note.data.take(120)}"
                            break
                        }
                        else -> {
                            if (verbose) log("ws notification: ${note.notificationType}")
                            if (note.notificationType !in QUIET) onRing()
                        }
                    }
                }
            } finally {
                ping.stop()
            }
            throw IllegalStateException(dropReason ?: "closed by server")
        }
    }
}
