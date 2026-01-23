package id.homebase.homebasekmppoc.prototype.lib.websockets

import id.homebase.homebasekmppoc.prototype.lib.http.SharedSecretEncryptedPayload
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
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
        private const val PONG_TIMEOUT_MS = 3_000L
        private const val RETRY_DELAY_MS = 500L
        private const val OFFLINE_PING_INTERVAL_MS = 5_000L
    }

    @Volatile
    private var lastPongAt: Long = 0L

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return

        lastPongAt = nowMs()

        job = scope.launch {
            while (true) {
                sendPingSafely()

                delay(PONG_TIMEOUT_MS)

                if (isPongFresh()) continue

                delay(RETRY_DELAY_MS)
                sendPingSafely()

                delay(RETRY_DELAY_MS)

                if (!isPongFresh()) {
                    onOffline()

                    // fallback mode
                    while (true) {
                        delay(OFFLINE_PING_INTERVAL_MS)
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
        return nowMs() - lastPongAt <= PONG_TIMEOUT_MS
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
