package id.homebase.core.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coalesces bursts of [trigger] calls into a single invocation of [action].
 *
 * Each call to [trigger] cancels the previously pending invocation and schedules a new one
 * after [delayMs]. The last call within a quiet window wins. Use this for expensive work
 * that should run once after a burst of requests settles — e.g. "reconnect the WebSocket
 * after the user activates one or more add-ons in quick succession".
 *
 * Thread-safe: a [Mutex] serialises the cancel + relaunch pair so concurrent callers never
 * leak overlapping jobs.
 */
internal class DebouncedAction(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    private val action: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private var job: Job? = null

    suspend fun trigger() {
        mutex.withLock {
            job?.cancel()
            job = scope.launch {
                delay(delayMs)
                action()
            }
        }
    }

    suspend fun cancel() {
        mutex.withLock {
            job?.cancel()
            job = null
        }
    }
}
