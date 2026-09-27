package id.homebase.core.diagnostics

import kotlinx.atomicfu.atomic
import kotlin.concurrent.Volatile

/**
 * Platform hook for a UI-thread liveness check that runs on a dedicated OS thread rather than
 * a coroutine dispatcher — see [MainThreadWatchdog]'s doc for why the coroutine-based loop alone
 * can't detect a stall caused by [kotlinx.coroutines.Dispatchers.Default]'s own pool being
 * exhausted (that pool starving takes the watchdog's own loop down with it).
 *
 * Registered by androidMain/jvmMain (a raw `Thread` posting to the real UI thread). Platforms
 * without a registered [Probe] (iOS, wasmJs) make [startIfAvailable] a no-op; those platforms
 * rely solely on [MainThreadWatchdog]'s coroutine-based wall-clock-gap detection, which is
 * dispatcher-agnostic.
 */
object MainThreadLivenessProbe {
    fun interface Probe {
        /** Starts the platform liveness check; [onStalled] may be called from any thread. */
        fun start(thresholdMs: Long, pollIntervalMs: Long, onStalled: (stalledMs: Long) -> Unit): Handle
    }

    fun interface Handle {
        fun stop()
    }

    @Volatile
    private var probe: Probe? = null

    /** Called once by platform code during app init. Idempotent (last registration wins). */
    fun register(probe: Probe) {
        this.probe = probe
    }

    /** Starts the registered platform probe, or returns `null` if none is registered here. */
    internal fun startIfAvailable(
        thresholdMs: Long,
        pollIntervalMs: Long,
        onStalled: (stalledMs: Long) -> Unit,
    ): Handle? = probe?.start(thresholdMs, pollIntervalMs, onStalled)
}

/**
 * The dedicated-thread probe loop, with the clock, sleep and UI-thread post injected so the
 * Android and JVM actuals share it and tests can drive it on a fake clock. [onStalled] fires once
 * at [thresholdMs] while the stall is still ongoing — a hang that never ends must still leave a
 * log line — and once more with the total when the UI thread recovers.
 */
internal fun runLivenessProbeLoop(
    thresholdMs: Long,
    pollIntervalMs: Long,
    pollStepMs: Long,
    isRunning: () -> Boolean,
    nowMs: () -> Long,
    sleepMs: (Long) -> Unit,
    postToMainThread: (() -> Unit) -> Unit,
    onStalled: (stalledMs: Long) -> Unit,
) {
    while (isRunning()) {
        val acked = atomic(false)
        val postedAtMs = nowMs()
        postToMainThread { acked.value = true }

        var reported = false
        // Wait out the real ack rather than re-posting, so a long hang leaves one sentinel queued.
        while (!acked.value && isRunning()) {
            if (!reported && nowMs() - postedAtMs >= thresholdMs) {
                onStalled(nowMs() - postedAtMs)
                reported = true
            }
            sleepMs(pollStepMs)
        }
        if (reported && acked.value) onStalled(nowMs() - postedAtMs)

        sleepMs(pollIntervalMs)
    }
}

/**
 * Registers this platform's [MainThreadLivenessProbe.Probe], if it has one. Called once from
 * [MainThreadWatchdog.start]; a no-op on platforms without a dedicated-thread implementation
 * (iOS, wasmJs), which rely solely on the coroutine-based wall-clock-gap detection instead.
 */
internal expect fun installMainThreadLivenessProbe()
