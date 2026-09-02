package id.homebase.core.diagnostics

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/**
 * Cross-platform main/UI-thread and whole-process stall detector.
 *
 * A background coroutine (on [workDispatcher], never the UI thread) posts a sentinel to
 * [mainDispatcher] every [tickIntervalMs]. If the sentinel does not run within [thresholdMs],
 * the UI thread is wedged: the watchdog captures its stack via the platform
 * [captureMainThreadStackTrace] hook and emits a WARN (throttled to one per [throttleMs]) into
 * `homebase.log` — so a freeze leaves usable evidence instead of nothing.
 *
 * That mechanism has a blind spot: it runs on [workDispatcher] (`Dispatchers.Default` by
 * default), so if the *whole process* stalls — most plausibly `Dispatchers.Default`'s own
 * limited-parallelism pool getting exhausted by blocking work dispatched to it elsewhere in the
 * app — the watchdog's own loop never gets scheduled and can't emit anything either. Two
 * production incidents hit exactly this: a real ~30s user-facing freeze with zero
 * `MainThreadWatchdog` log lines. Two additional, independent detectors close that gap:
 *
 *  - **Wall-clock gap detection** (this loop): records a checkpoint immediately before
 *    `delay(tickIntervalMs)` and compares it to the wall-clock gap after waking up. If the gap
 *    vastly exceeds what was requested, the loop itself was starved — logged as
 *    [StallKind.WatchdogStarved] the instant it recovers. Needs nothing to run *during* the
 *    freeze.
 *  - **[MainThreadLivenessProbe]** (Android/JVM only): a raw OS thread, scheduled directly by
 *    the OS rather than any coroutine dispatcher, independently checks the UI thread is alive.
 *    It survives `Dispatchers.Default` pool exhaustion that would otherwise silence this loop
 *    entirely.
 *
 * Both detectors report through one shared, throttled [StallReporter], so the same incident
 * can't produce two log lines.
 *
 * Platform notes (see `captureMainThreadStackTrace` actuals):
 *  - Android: stack comes from the main `Looper` thread, ~1s before the OS ANR cutoff.
 *  - Desktop (JVM): stack comes from the AWT event-dispatch thread (Compose's Main dispatcher).
 *  - iOS: detection works, but capturing another thread's backtrace from Kotlin/Native is not
 *    supported, so no stack is attached — the WARN still records that the UI thread stalled and
 *    for how long. (iOS also has its own OS-level main-thread watchdog for launch/resume.)
 */
class MainThreadWatchdog(
    private val thresholdMs: Long = 4_000,
    private val tickIntervalMs: Long = 1_000,
    throttleMs: Long = 30_000,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    log: (String) -> Unit = { Logger.w(tag = TAG) { it } },
) {
    private val scope = CoroutineScope(SupervisorJob() + workDispatcher)
    private val timeOrigin = TimeSource.Monotonic.markNow()
    private fun nowMs(): Long = timeOrigin.elapsedNow().inWholeMilliseconds

    private val reporter = StallReporter(throttleMs = throttleMs, nowMs = ::nowMs, log = log)
    private var livenessHandle: MainThreadLivenessProbe.Handle? = null

    fun start() {
        installMainThreadLivenessProbe()
        installMemoryDiagnostics()
        livenessHandle = MainThreadLivenessProbe.startIfAvailable(
            thresholdMs = thresholdMs,
            pollIntervalMs = tickIntervalMs,
        ) { stalledMs ->
            val stack = captureMainThreadStackTrace()
            reporter.reportIfDue {
                renderStallMessage(
                    StallEvent(
                        kind = StallKind.MainThreadBlock,
                        source = StallSource.DedicatedThread,
                        observedMs = stalledMs,
                        memory = MemoryDiagnostics.capture(),
                    ),
                    stack = stack,
                )
            }
        }

        scope.launch {
            while (isActive) {
                val pong = CompletableDeferred<Unit>()
                val postedAt = nowMs()
                // Post the sentinel to the UI dispatcher. If it's blocked, this never runs.
                launch(mainDispatcher) { pong.complete(Unit) }

                val acked = withTimeoutOrNull(thresholdMs) { pong.await() }
                if (acked == null) {
                    val stalledMs = nowMs() - postedAt
                    val stack = captureMainThreadStackTrace()
                    reporter.reportIfDue {
                        renderStallMessage(
                            StallEvent(
                                kind = StallKind.MainThreadBlock,
                                source = StallSource.CoroutineLoop,
                                observedMs = stalledMs,
                                memory = MemoryDiagnostics.capture(),
                            ),
                            stack = stack,
                        )
                    }
                    // Wait for the UI thread to recover before ticking again, so a long hang
                    // leaves only one outstanding sentinel rather than one per tick.
                    pong.await()
                }

                // Checkpoint immediately around the suspend point that depends on workDispatcher
                // rescheduling us: if that takes far longer than requested, the watchdog's own
                // loop — not just the UI thread — was starved.
                val checkpointMs = nowMs()
                delay(tickIntervalMs)
                val actualGapMs = nowMs() - checkpointMs
                val starvedMs = detectWatchdogStarvation(expectedGapMs = tickIntervalMs, actualGapMs = actualGapMs)
                if (starvedMs != null) {
                    val stack = captureMainThreadStackTrace()
                    reporter.reportIfDue {
                        renderStallMessage(
                            StallEvent(
                                kind = StallKind.WatchdogStarved,
                                source = StallSource.CoroutineLoop,
                                observedMs = starvedMs,
                                memory = MemoryDiagnostics.capture(),
                            ),
                            stack = stack,
                        )
                    }
                }
            }
        }
    }

    fun stop() {
        scope.cancel()
        livenessHandle?.stop()
        livenessHandle = null
    }

    companion object {
        private const val TAG = "MainThreadWatchdog"
    }
}

/**
 * Renders the current UI/main thread's stack (top [maxFrames] frames), or `null` if the platform
 * cannot capture another thread's stack from the watchdog thread.
 */
internal expect fun captureMainThreadStackTrace(maxFrames: Int = 60): String?
