package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import id.homebase.api.client.calling.CallIceServer
import id.homebase.api.client.calling.IceServersResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** The servers one call gathers with. [expiresAtMs] is when its TURN login stops opening relays; null without one. */
class CallIceServers(val servers: List<CallIceServer>, val expiresAtMs: Long? = null, val fromServer: Boolean = true) {
    fun isFresh(nowMs: Long) = expiresAtMs != null && expiresAtMs - nowMs > LOGIN_MARGIN_MS

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
    private val fetch: (suspend (peer: String) -> IceServersResponse)?,
    private val handshakeStunUrls: suspend () -> List<String>,
    private val timeoutMs: Long = FETCH_TIMEOUT_MS,
) {
    suspend fun forCall(peer: String): CallIceServers {
        val fetched = fetch?.let { ask ->
            try {
                withTimeoutOrNull(timeoutMs) { ask(peer) }.also { if (it == null) log.w { "no ICE servers within ${timeoutMs}ms" } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "ICE servers request failed" }
                null
            }
        }
        if (fetched != null && fetched.iceServers.isNotEmpty()) {
            return CallIceServers(fetched.iceServers, fetched.expiresAt?.milliseconds)
        }
        val stun = handshakeStunUrls()
        return CallIceServers(if (stun.isEmpty()) emptyList() else listOf(CallIceServer(stun)), fromServer = false)
    }

    private companion object {
        val log = Logger.withTag("CallIceServers")
        const val FETCH_TIMEOUT_MS = 3_000L
    }
}

// A relay is opened as gathering starts, so a login this close to expiry is asked for again.
private const val LOGIN_MARGIN_MS = 30_000L
