package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Collapses a burst of [request] calls into at most one in-flight [action] run.
 *
 * Backed by a CONFLATED channel (keeps only the latest payload) drained by a SINGLE collector
 * coroutine that waits [debounceMs] — to batch a tight burst — then runs [action]. Because the
 * collector is a plain `for` loop, runs never overlap, and while one runs only the latest
 * pending payload survives in the channel. So a storm of N requests collapses to ~one run
 * (plus at most one coalesced rerun for whatever arrived mid-run) instead of N concurrent runs.
 *
 * Built for unread-count enrichment in [ConversationStream]: each run is a full-DB GROUP BY
 * plus a single-writer mirror upsert, and firing one per WebSocket batch / DriveSync-Stopped
 * during a sync storm spawned N concurrent passes that piled onto the read lane and the writer
 * (observed at 47–131s in homebase.log).
 *
 * [action] exceptions are caught and logged so a single failure doesn't kill the collector.
 *
 * The collector lives for the lifetime of [scope]; cancel the scope to stop it.
 */
class CoalescingTrigger<T>(
    scope: CoroutineScope,
    private val debounceMs: Long,
    private val action: suspend (T) -> Unit,
) {
    private val requests = Channel<T>(Channel.CONFLATED)

    init {
        scope.launch {
            for (value in requests) {
                if (debounceMs > 0) delay(debounceMs)
                try {
                    action(value)
                } catch (e: Exception) {
                    Logger.e(e) { "CoalescingTrigger action failed: ${e.message}" }
                }
            }
        }
    }

    /** Request a run. Never blocks; coalesces with any already-pending request. */
    fun request(value: T) {
        requests.trySend(value)
    }
}
