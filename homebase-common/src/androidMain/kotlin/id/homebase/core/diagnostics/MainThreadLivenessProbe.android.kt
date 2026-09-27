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
            runLivenessProbeLoop(
                thresholdMs = thresholdMs,
                pollIntervalMs = pollIntervalMs,
                isRunning = { running.value },
                sleepMs = Thread::sleep,
                postToMainThread = { handler.post(it) },
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
}

internal actual fun installMainThreadLivenessProbe() {
    MainThreadLivenessProbe.register(AndroidMainThreadLivenessProbe())
}
