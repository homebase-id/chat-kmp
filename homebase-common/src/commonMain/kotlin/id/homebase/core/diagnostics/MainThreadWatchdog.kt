package id.homebase.core.diagnostics

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
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
 * Cross-platform main/UI-thread stall detector.
 *
 * A background coroutine (on [Dispatchers.Default], never the UI thread) posts a sentinel to
 * [Dispatchers.Main] every [tickIntervalMs]. If the sentinel does not run within [thresholdMs],
 * the UI thread is wedged: the watchdog captures its stack via the platform
 * [captureMainThreadStackTrace] hook and emits a single WARN (throttled to one per [throttleMs])
 * into `homebase.log` — so a freeze leaves usable evidence instead of nothing.
 *
 * This replaces the Android-only `MainThreadWatchdog` so desktop and iOS get the same breadcrumb;
 * previously a desktop UI stall produced no log at all.
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
    private val throttleMs: Long = 30_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        // Startup marker so a log can confirm the watchdog is actually present AND which
        // threshold this build uses — the loop below is otherwise silent until it fires, so
        // "no MainThreadWatchdog line" was ambiguous (not-in-build vs no-stall). The threshold
        // value also identifies the build: 800 = the cold-tap diagnostic build, 4000 = default.
        Logger.i(tag = TAG) { "MainThreadWatchdog started (threshold=${thresholdMs}ms, tick=${tickIntervalMs}ms)" }
        scope.launch {
            val timeSource = TimeSource.Monotonic
            var lastReportMark = timeSource.markNow()
            var hasReported = false

            while (isActive) {
                val pong = CompletableDeferred<Unit>()
                val postedAt = timeSource.markNow()
                // Post the sentinel to the UI dispatcher. If it's blocked, this never runs.
                launch(Dispatchers.Main) { pong.complete(Unit) }

                val acked = withTimeoutOrNull(thresholdMs) { pong.await() }
                if (acked == null) {
                    if (!hasReported || lastReportMark.elapsedNow().inWholeMilliseconds >= throttleMs) {
                        hasReported = true
                        lastReportMark = timeSource.markNow()
                        val stalledMs = postedAt.elapsedNow().inWholeMilliseconds
                        val stack = captureMainThreadStackTrace()
                        val rendered = buildString {
                            appendLine(
                                "Main/UI thread stalled >${stalledMs}ms — likely a recomposition " +
                                    "loop or blocking I/O on the UI dispatcher."
                            )
                            if (stack != null) {
                                appendLine("UI thread stack at sample:")
                                append(stack)
                            } else {
                                appendLine("(UI thread stack capture not supported on this platform)")
                            }
                        }
                        Logger.w(tag = TAG) { rendered }
                    }
                    // Wait for the UI thread to recover before ticking again, so a long hang
                    // leaves only one outstanding sentinel rather than one per tick.
                    pong.await()
                }

                delay(tickIntervalMs)
            }
        }
    }

    fun stop() {
        scope.cancel()
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
