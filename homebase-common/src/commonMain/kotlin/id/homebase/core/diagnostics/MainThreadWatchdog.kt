package id.homebase.core.diagnostics

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
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
 * That mechanism has a blind spot: it runs on [workDispatcher] (a dedicated
 * single thread by default, so `Dispatchers.Default` pool exhaustion cannot starve it), but a
 * *whole-process* stall would still leave it unscheduled and unable to emit anything. Two
 * production incidents hit exactly this: a real ~30s user-facing freeze with zero
 * `MainThreadWatchdog` log lines. Two additional, independent detectors close that gap:
 *
 *  - **Wall-clock gap detection** (this loop): records a checkpoint immediately before
 *    `delay(tickIntervalMs)` and compares it to the wall-clock gap after waking up. If the gap
 *    vastly exceeds what was requested, the loop itself was starved — logged as
 *    [StallKind.WatchdogStarved] the instant it recovers. Needs nothing to run *during* the
 *    freeze. Process CPU time is sampled either side of the same gap so the breadcrumb says
 *    whether the OS suspended us or we froze ourselves (see [classifyStallCause]).
 *  - **[MainThreadLivenessProbe]** (Android/JVM only): a raw OS thread, scheduled directly by
 *    the OS rather than any coroutine dispatcher, independently checks the UI thread is alive.
 *    It survives `Dispatchers.Default` pool exhaustion that would otherwise silence this loop
 *    entirely.
 *
 * Both detectors report through one shared, throttled [StallReporter], so the same incident
 * can't produce two log lines.
 *
 * A main-thread block is reported at [thresholdMs] while still ongoing, but a starved watchdog
 * loop can only be reported once it runs again, so a process killed while starved writes nothing.
 * [ProcessHeartbeat] closes that from the next launch.
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
    workDispatcher: CoroutineDispatcher? = null,
    private val log: (String) -> Unit = { Logger.w(tag = TAG) { it } },
    private val heartbeat: ProcessHeartbeat? = null,
    private val heartbeatIntervalMs: Long = 5_000,
    private val aliveLogIntervalMs: Long = 600_000,
    private val lifecycleLog: (Severity, String, Throwable?) -> Unit =
        { severity, message, throwable -> Logger.log(severity, TAG, throwable, message) },
    private val captureStack: () -> String? = { captureMainThreadStackTrace() },
    private val useLivenessProbe: Boolean = true,
) {
    // Own thread by default: on Dispatchers.Default the loop shares its pool with the work a
    // wedge would exhaust, so it could go silent exactly when it is needed.
    private val workDispatcher: CoroutineDispatcher = workDispatcher ?: createWatchdogDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + this.workDispatcher)
    private val timeOrigin = TimeSource.Monotonic.markNow()
    private fun nowMs(): Long = timeOrigin.elapsedNow().inWholeMilliseconds

    private val reporter = StallReporter(throttleMs = throttleMs, nowMs = ::nowMs, log = log)
    private var livenessHandle: MainThreadLivenessProbe.Handle? = null

    @Volatile
    private var stopRequested = false

    fun start() {
        heartbeat?.let { hb ->
            hb.postMortem()?.let(log)
            hb.beat()
        }
        installMainThreadLivenessProbe()
        installMemoryDiagnostics()
        if (useLivenessProbe) {
            livenessHandle = MainThreadLivenessProbe.startIfAvailable(
                thresholdMs = thresholdMs,
                pollIntervalMs = tickIntervalMs,
            ) { stalledMs ->
                val stack = captureStack()
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
        }

        scope.launch { runLoop() }
    }

    private suspend fun CoroutineScope.runLoop() {
        lifecycleLog(
            Severity.Info,
            "Watchdog loop started: thread=${currentThreadName()} dispatcher=$workDispatcher " +
                "main=$mainDispatcher thresholdMs=$thresholdMs tickMs=$tickIntervalMs",
            null,
        )
        try {
            var lastBeatMs = nowMs()
            var lastAliveMs = lastBeatMs
            var iterations = 0L
            var consecutiveFailures = 0
            while (currentCoroutineContext().isActive) {
                try {
                    tick()
                    consecutiveFailures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    consecutiveFailures++
                    if (consecutiveFailures <= 3 || consecutiveFailures % 100 == 0) {
                        lifecycleLog(
                            Severity.Error,
                            "Watchdog loop iteration failed (consecutive=$consecutiveFailures); continuing",
                            e,
                        )
                    }
                    delay(tickIntervalMs)
                }
                iterations++

                val now = nowMs()
                if (heartbeat != null && now - lastBeatMs >= heartbeatIntervalMs) {
                    lastBeatMs = now
                    heartbeat.beat()
                }
                if (now - lastAliveMs >= aliveLogIntervalMs) {
                    lastAliveMs = now
                    lifecycleLog(Severity.Info, "Watchdog loop alive: iterations=$iterations", null)
                }
            }
        } catch (e: CancellationException) {
            val why = if (stopRequested) "stop() called" else "cancelled without stop()"
            lifecycleLog(Severity.Info, "Watchdog loop stopped: $why, cause=${e.cause ?: e.message}", e)
            throw e
        } catch (e: Throwable) {
            lifecycleLog(Severity.Error, "Watchdog loop died", e)
            throw e
        }
    }

    private suspend fun CoroutineScope.tick() {
        val pong = CompletableDeferred<Unit>()
        val postedAt = nowMs()
        // Post the sentinel to the UI dispatcher. If it's blocked, this never runs.
        launch(mainDispatcher) { pong.complete(Unit) }

        val acked = withTimeoutOrNull(thresholdMs) { pong.await() }
        if (acked == null) {
            val stalledMs = nowMs() - postedAt
            val stack = captureStack()
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
        val checkpointTimes = captureProcessTimes()
        delay(tickIntervalMs)
        val actualGapMs = nowMs() - checkpointMs
        val starvedMs = detectWatchdogStarvation(expectedGapMs = tickIntervalMs, actualGapMs = actualGapMs)
        if (starvedMs != null) {
            val processDelta = processTimesDelta(checkpointTimes, captureProcessTimes())
            val stack = captureStack()
            reporter.reportIfDue {
                renderStallMessage(
                    StallEvent(
                        kind = StallKind.WatchdogStarved,
                        source = StallSource.CoroutineLoop,
                        observedMs = starvedMs,
                        memory = MemoryDiagnostics.capture(),
                        processDelta = processDelta,
                    ),
                    stack = stack,
                )
            }
        }
    }

    fun stop() {
        stopRequested = true
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

/** A single thread nothing else shares, so the watchdog loop can't be starved by pool exhaustion. */
internal expect fun createWatchdogDispatcher(): CoroutineDispatcher

internal expect fun currentThreadName(): String
