package id.homebase.api.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Fails with [stalled] when more than [idle] passes between two `progress` calls, from the first until `progress(true)`.
 * Engine socket timeouts can't be relied on: the web engine has none, and Darwin's let an HTTP/2 upload sit for 30+ minutes.
 */
internal suspend fun <T> withIdleTimeout(
    idle: Duration,
    stalled: () -> Throwable,
    block: suspend (progress: (done: Boolean) -> Unit) -> T,
): T = coroutineScope {
    val progress = Channel<Boolean>(Channel.CONFLATED)
    // Real time on Dispatchers.Default: a test's virtual clock would skip straight past [idle].
    val watchdog = launch(Dispatchers.Default) {
        var done = progress.receive()
        while (!done) done = withTimeoutOrNull(idle) { progress.receive() } ?: throw stalled()
    }
    try {
        block { done -> progress.trySend(done) }
    } finally {
        watchdog.cancel()
    }
}
