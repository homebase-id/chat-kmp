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
            runLivenessProbeLoop(
                thresholdMs = thresholdMs,
                pollIntervalMs = pollIntervalMs,
                pollStepMs = POLL_STEP_MS,
                isRunning = { running.value },
                nowMs = { System.nanoTime() / 1_000_000 },
                sleepMs = Thread::sleep,
                postToMainThread = { SwingUtilities.invokeLater(it) },
                onStalled = onStalled,
            )
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
