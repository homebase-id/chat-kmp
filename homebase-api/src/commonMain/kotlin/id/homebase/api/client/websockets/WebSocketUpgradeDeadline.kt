package id.homebase.api.client.websockets

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long a connect attempt may take to reach the 101 Switching Protocols (#1921). Covers DNS,
 * TCP, TLS and the upgrade response; comfortably above CIO's 5 s TCP connect timeout.
 *
 * This deadline also applies on mobile. Android's OkHttp gives up first (10 s idle read timeout),
 * except while a DNS lookup is stalled. On iOS, Darwin's default is a 60 s idle timeout, so this
 * value becomes the effective connect limit there. 30 s still leaves room for a slow cellular
 * connect, while Desktop recovers within about half a minute.
 */
internal const val WS_UPGRADE_TIMEOUT_MS = 30_000L

/**
 * A connect attempt that did not reach the 101 within its deadline. Deliberately NOT a
 * CancellationException: the reconnect loops rethrow those, which would end the loop instead of
 * retrying.
 */
class WebSocketUpgradeTimeoutException(timeoutMs: Long) :
    Exception("WebSocket upgrade did not complete within ${timeoutMs}ms")

/**
 * Runs [block] — a `client.webSocket(...)` call — and fails it with
 * [WebSocketUpgradeTimeoutException] unless it calls `upgraded()` within [timeoutMs].
 *
 * Why (#1921): Ktor CIO's connectTimeout covers only the TCP connect. The TLS handshake and the
 * wait for the 101 run with an infinite socket timeout, and requestTimeout is disabled for upgrade
 * requests. One attempt that connects TCP just as the link dies hangs there forever, and the
 * reconnect loop never iterates again (Desktop stays offline until restart). The handshake and
 * ping watchdogs only start after the 101, so they can't catch it.
 *
 * Call `upgraded()` first thing in the session block. The deadline then no longer applies, and
 * the session may run as long as it likes.
 */
internal suspend fun <T> withWebSocketUpgradeDeadline(
    timeoutMs: Long = WS_UPGRADE_TIMEOUT_MS,
    block: suspend (upgraded: () -> Unit) -> T,
): T = coroutineScope {
    val watchdog = launch {
        delay(timeoutMs)
        throw WebSocketUpgradeTimeoutException(timeoutMs)
    }
    try {
        block { watchdog.cancel() }
    } finally {
        watchdog.cancel()
    }
}
