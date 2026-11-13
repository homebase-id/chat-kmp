package id.homebase.chat.services.calling

import co.touchlab.kermit.Logger
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.calling.CallIceServer
import id.homebase.api.client.calling.IceServerAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException

/** The servers one call gathers with, and for how long after they arrived they may be used without asking again. */
class CallIceServers(val servers: List<CallIceServer>, val reuseForMs: Long, val fromServer: Boolean = true) {
    val hasLogin get() = servers.any { it.credential != null }

    override fun toString() = "turn=$hasLogin servers=${servers.size} reuseFor=${reuseForMs / 1000}s" + if (fromServer) "" else " (fallback)"

    companion object {
        val None = CallIceServers(emptyList(), reuseForMs = 0, fromServer = false)
    }
}

/**
 * Asks the device's own server for a call's STUN and TURN login. When that fails or is slow (an older server, no network
 * yet), the call gathers with the STUN URLs from the socket handshake.
 */
class CallIceServerSource(
    private val fetch: suspend (peer: String) -> IceServerAnswer,
    private val handshakeStunUrls: StateFlow<List<String>>,
    private val timeoutMs: Long = WAIT_MS,
) {
    suspend fun forCall(peer: String): CallIceServers = coroutineScope {
        // Both wait on the same thing (the network after a wake), so they share one wait.
        val stun = async { handshakeStunUrls.awaitStunUrls(timeoutMs) }
        var olderServer = false
        val fetched = try {
            withTimeoutOrNull(timeoutMs) { fetch(peer) }.also { if (it == null) log.w { "no ICE servers within ${timeoutMs}ms" } }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            log.w { "ICE servers request cancelled under us" }
            null
        } catch (e: NotFoundException) {
            log.w { "this server hands out no ICE servers" }
            olderServer = true
            null
        } catch (e: SerializationException) {
            // Its message quotes the body, and the body holds the TURN login.
            log.w { "ICE servers answer unreadable: ${e::class.simpleName}" }
            null
        } catch (e: Exception) {
            log.w(e) { "ICE servers request failed" }
            null
        }
        if (fetched != null && fetched.iceServers.isNotEmpty()) {
            stun.cancel()
            val validFor = fetched.loginValidForMs
            // Without a login the server may only be over its per-minute limit, so it is asked again once that has passed.
            return@coroutineScope CallIceServers(fetched.iceServers, if (validFor != null) validFor - LOGIN_MARGIN_MS else NO_LOGIN_REUSE_MS)
        }
        val urls = stun.await()
        CallIceServers(
            if (urls.isEmpty()) emptyList() else listOf(CallIceServer(urls)),
            reuseForMs = if (olderServer) Long.MAX_VALUE else FAILED_REUSE_MS,
            fromServer = false,
        )
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
private const val NO_LOGIN_REUSE_MS = 60_000L
private const val FAILED_REUSE_MS = 5_000L
