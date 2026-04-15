package id.homebase.api.client.websockets

import id.homebase.api.client.SharedSecretEncryptedPayload
import id.homebase.api.serialization.OdinSystemSerializer
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Clock

class WebSocketPingSupervisor(
    private val scope: CoroutineScope,
    private val sessionProvider: suspend () -> DefaultClientWebSocketSession?,
    private val encrypt: suspend (WebsocketCommand) -> SharedSecretEncryptedPayload,
    private val onOnline: suspend () -> Unit,
    private val onOffline: suspend () -> Unit,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) {

    companion object {
        private const val PONG_TIMEOUT_MS = 10_000L
        private const val RETRY_DELAY_MS = 2_000L
        private const val OFFLINE_PING_INTERVAL_MS = 10_000L

        private const val PONG_TIMEOUT_BACKGROUND_MS = 60_000L
        private const val OFFLINE_PING_INTERVAL_BACKGROUND_MS = 60_000L
    }

    @Volatile
    var isInForeground: Boolean = true

    @Volatile
    private var lastPongAt: Long = 0L

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return

        lastPongAt = nowMs()

        job = scope.launch {
            while (true) {
                sendPingSafely()

                val pongTimeout = if (isInForeground) PONG_TIMEOUT_MS else PONG_TIMEOUT_BACKGROUND_MS
                delay(pongTimeout)

                if (isPongFresh()) continue

                delay(RETRY_DELAY_MS)
                sendPingSafely()

                delay(RETRY_DELAY_MS)

                if (!isPongFresh()) {
                    onOffline()

                    // fallback mode
                    while (true) {
                        val offlineInterval = if (isInForeground) OFFLINE_PING_INTERVAL_MS else OFFLINE_PING_INTERVAL_BACKGROUND_MS
                        delay(offlineInterval)
                        sendPingSafely()

                        if (isPongFresh()) {
                            onOnline()
                            break
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun notifyPongReceived() {
        lastPongAt = nowMs()
    }

    private fun isPongFresh(): Boolean {
        val timeout = if (isInForeground) PONG_TIMEOUT_MS else PONG_TIMEOUT_BACKGROUND_MS
        return nowMs() - lastPongAt <= timeout
    }

    fun notifySessionReconnected() {
        lastPongAt = nowMs()
    }

    private suspend fun sendPingSafely() {
        val session = sessionProvider() ?: return

        val command = WebsocketCommand(
            command = "ping",
            data = "ping"
        )

        val encrypted = encrypt(command)
        val json = OdinSystemSerializer.serialize(encrypted)

        session.send(Frame.Text(json))
    }
}
