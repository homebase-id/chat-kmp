package id.homebase.agent

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

const val IDLE_CONNECTED_MS = 60_000L
const val IDLE_DISCONNECTED_MS = 10_000L
const val DEBOUNCE_MS = 300L
const val MIN_POLL_SPACING_MS = 2_000L
const val STABLE_CONNECTION_MS = 30_000L
private const val BACKOFF_START_MS = 1_000L
private const val BACKOFF_MAX_MS = 60_000L

enum class Transport { AUTO, POLL }

fun parseTransport(value: String?): Transport? = when (value?.trim()?.lowercase()) {
    null, "", "auto" -> Transport.AUTO
    "poll" -> Transport.POLL
    else -> null
}

fun backoffMs(attempt: Int): Long = minOf(BACKOFF_START_MS shl attempt.coerceIn(0, 10), BACKOFF_MAX_MS)

class PollWaker(
    private val connectedIdleMs: Long = IDLE_CONNECTED_MS,
    private val disconnectedIdleMs: Long = IDLE_DISCONNECTED_MS,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val spacingMs: Long = MIN_POLL_SPACING_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val rings = Channel<Unit>(Channel.CONFLATED)
    private val changes = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    var connected = false
        private set

    fun ring() {
        rings.trySend(Unit)
    }

    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        changes.trySend(Unit)
        if (value) ring()
    }

    suspend fun await() {
        val started = now()
        while (true) {
            val rang = withTimeoutOrNull(if (connected) connectedIdleMs else disconnectedIdleMs) {
                select<Boolean> {
                    rings.onReceive { true }
                    changes.onReceive { false }
                }
            } ?: return
            if (!rang) continue
            delay(debounceMs)
            (spacingMs - (now() - started)).takeIf { it > 0 }?.let { delay(it) }
            rings.tryReceive()
            return
        }
    }
}

typealias Connector = suspend (onUp: () -> Unit, onRing: () -> Unit) -> Unit

suspend fun runDoorbell(connect: Connector, waker: PollWaker, log: (String) -> Unit, now: () -> Long = System::currentTimeMillis) {
    var attempt = 0
    var down: Boolean? = null
    var upAt = 0L
    while (true) {
        val reason = try {
            connect(
                {
                    upAt = now()
                    waker.setConnected(true)
                    down = false
                    log("transport: websocket connected")
                },
                waker::ring,
            )
            "closed"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e::class.simpleName ?: "error"
        }
        if (down == false && now() - upAt >= STABLE_CONNECTION_MS) attempt = 0
        waker.setConnected(false)
        if (down != true) log("transport: poll fallback ($reason)")
        down = true
        delay(backoffMs(attempt++))
    }
}

fun startDoorbell(scope: CoroutineScope, transport: Transport, connect: Connector, waker: PollWaker, log: (String) -> Unit): Job? =
    if (transport == Transport.POLL) null else scope.launch { runDoorbell(connect, waker, log) }
