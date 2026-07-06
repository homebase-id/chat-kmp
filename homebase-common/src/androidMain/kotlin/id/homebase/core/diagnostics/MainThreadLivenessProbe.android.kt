package id.homebase.core.diagnostics

import android.os.Handler
import android.os.Looper
import kotlinx.atomicfu.atomic

/**
 * Android liveness probe for [MainThreadLivenessProbe]: a raw `Thread`, scheduled directly by
 * the OS rather than any coroutine dispatcher, that independently posts a sentinel to the main
 * `Looper` and polls for it. Unlike [MainThreadWatchdog]'s own coroutine loop, this thread keeps
 * running even if `Dispatchers.Default`'s pool is fully exhausted by blocking work dispatched to
 * it elsewhere in the app — the scenario that left two production freezes with no breadcrumb.
 */
private class AndroidMainThreadLivenessProbe : MainThreadLivenessProbe.Probe {
    override fun start(
        thresholdMs: Long,
        pollIntervalMs: Long,
        onStalled: (stalledMs: Long) -> Unit,
    ): MainThreadLivenessProbe.Handle {
        val handler = Handler(Looper.getMainLooper())
        val running = atomic(true)
        val thread = Thread({
            while (running.value) {
                val acked = atomic(false)
                val postedAtNanos = System.nanoTime()
                handler.post { acked.value = true }

                val deadlineNanos = postedAtNanos + thresholdMs * 1_000_000
                while (!acked.value && System.nanoTime() < deadlineNanos) {
                    Thread.sleep(POLL_STEP_MS)
                }
                if (!acked.value) {
                    // Don't pile up posts on the handler queue: wait out the real ack before
                    // reporting/ticking again, mirroring the coroutine loop's own behavior.
                    while (!acked.value && running.value) Thread.sleep(POLL_STEP_MS)
                    val stalledMs = (System.nanoTime() - postedAtNanos) / 1_000_000
                    onStalled(stalledMs)
                }

                Thread.sleep(pollIntervalMs)
            }
        }, "MainThreadLivenessProbe").apply {
            isDaemon = true
            start()
        }

        return MainThreadLivenessProbe.Handle {
            running.value = false
            thread.interrupt()
        }
    }

    private companion object {
        const val POLL_STEP_MS = 50L
    }
}

internal actual fun installMainThreadLivenessProbe() {
    MainThreadLivenessProbe.register(AndroidMainThreadLivenessProbe())
}
