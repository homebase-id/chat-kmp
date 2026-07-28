package id.homebase.core.diagnostics

import kotlinx.atomicfu.atomic
import javax.swing.SwingUtilities

/**
 * Desktop (JVM) liveness probe for [MainThreadLivenessProbe]: a raw `Thread`, scheduled directly
 * by the OS rather than any coroutine dispatcher, that independently posts a sentinel to the AWT
 * event-dispatch thread (Compose Desktop's `Dispatchers.Main`) and polls for it. Survives
 * `Dispatchers.Default`'s pool being fully exhausted by blocking work dispatched to it elsewhere
 * in the app — unlike [MainThreadWatchdog]'s own coroutine loop.
 */
private class JvmMainThreadLivenessProbe : MainThreadLivenessProbe.Probe {
    override fun start(
        thresholdMs: Long,
        pollIntervalMs: Long,
        onStalled: (stalledMs: Long) -> Unit,
    ): MainThreadLivenessProbe.Handle {
        val running = atomic(true)
        val thread = Thread({
            while (running.value) {
                val acked = atomic(false)
                val postedAtNanos = System.nanoTime()
                SwingUtilities.invokeLater { acked.value = true }

                val deadlineNanos = postedAtNanos + thresholdMs * 1_000_000
                while (!acked.value && System.nanoTime() < deadlineNanos) {
                    Thread.sleep(POLL_STEP_MS)
                }
                if (!acked.value) {
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
    MainThreadLivenessProbe.register(JvmMainThreadLivenessProbe())
}
