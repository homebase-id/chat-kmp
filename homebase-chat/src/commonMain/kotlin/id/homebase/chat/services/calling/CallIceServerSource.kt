package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import id.homebase.api.client.calling.CallIceServer
import id.homebase.api.client.calling.IceServersResponse
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The servers one call gathers with. [expiresAtMs] is when its TURN login stops opening relays; null without one. */
class CallIceServers(
    val servers: List<CallIceServer>,
    val expiresAtMs: Long? = null,
    val fromServer: Boolean = true,
    val fetchedAtMs: Long = 0,
) {
    /** A fallback is only as good as the network was when it was made, so it is asked for again unless it is brand new. */
    fun reusableAt(nowMs: Long) =
        if (fromServer) expiresAtMs == null || expiresAtMs - nowMs > LOGIN_MARGIN_MS else nowMs - fetchedAtMs < FALLBACK_REUSE_MS

    override fun toString() = "turn=${expiresAtMs != null} servers=${servers.size}" + if (fromServer) "" else " (fallback)"

    companion object {
        val None = CallIceServers(emptyList(), fromServer = false)
    }
}

/**
 * Asks the device's own server for a call's STUN and TURN login. When that fails or is slow (an older server, no network
 * yet), the call gathers with the STUN URLs from the socket handshake, as it did before TURN.
 */
class CallIceServerSource(
    private val fetch: suspend (peer: String) -> IceServersResponse,
    private val handshakeStunUrls: StateFlow<List<String>>,
    private val timeoutMs: Long = WAIT_MS,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun forCall(peer: String): CallIceServers = coroutineScope {
        // Both wait on the same thing (the network after a wake), so they share one wait.
        val stun = async { handshakeStunUrls.awaitStunUrls(timeoutMs) }
        val fetched = try {
            withTimeoutOrNull(timeoutMs) { fetch(peer) }.also { if (it == null) log.w { "no ICE servers within ${timeoutMs}ms" } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "ICE servers request failed" }
            null
        }
        if (fetched != null && fetched.iceServers.isNotEmpty()) {
            stun.cancel()
            return@coroutineScope CallIceServers(fetched.iceServers, fetched.expiresAt?.milliseconds, fetchedAtMs = clock())
        }
        val urls = stun.await()
        CallIceServers(if (urls.isEmpty()) emptyList() else listOf(CallIceServer(urls)), fromServer = false, fetchedAtMs = clock())
    }

    private companion object {
        val log = Logger.withTag("CallIceServers")
    }
}

/** The URLs arrive with the websocket handshake, which a call placed or answered right after a cold push wake can beat. */
internal suspend fun StateFlow<List<String>>.awaitStunUrls(timeoutMs: Long = WAIT_MS): List<String> =
    value.ifEmpty { withTimeoutOrNull(timeoutMs) { first { it.isNotEmpty() } } ?: emptyList() }

private const val WAIT_MS = 3_000L

// A relay is opened as gathering starts, so a login this close to expiry is asked for again.
private const val LOGIN_MARGIN_MS = 30_000L
private const val FALLBACK_REUSE_MS = 5_000L
