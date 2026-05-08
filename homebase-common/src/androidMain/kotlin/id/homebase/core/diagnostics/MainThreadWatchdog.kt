package id.homebase.core.diagnostics

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import co.touchlab.kermit.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Detects main-thread stalls before Android's ANR machinery does.
 *
 * A background thread posts a sentinel to the main looper every [tickIntervalMs]; if the sentinel
 * does not run within [thresholdMs], the watchdog captures the main thread's stack trace and emits
 * a single WARN log line (per [throttleMs] window) so we get a *deobfuscated-on-mapping* snapshot
 * of where the main thread is spinning, before the OS kills the process at ~5s.
 *
 * Why this exists: build 1.3.1388 ANR'd in a Compose recomposition loop where the system-server's
 * crash dump landed only after the kill, with R8 frames at the input-dispatcher level — useless
 * for diagnosing the actual loop site. This watchdog logs the main-thread stack 1s before the ANR
 * cutoff, into the same `homebase.log` file we already collect.
 */
class MainThreadWatchdog(
    private val thresholdMs: Long = 4_000,
    private val tickIntervalMs: Long = 1_000,
    private val throttleMs: Long = 30_000,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdogThread = HandlerThread("MainThreadWatchdog").apply { start() }
    private val watchdogHandler = Handler(watchdogThread.looper)
    private var lastReportElapsedMs = 0L

    fun start() {
        watchdogHandler.post(tickRunnable)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            val acked = AtomicBoolean(false)
            mainHandler.post { acked.set(true) }

            val deadline = SystemClock.uptimeMillis() + thresholdMs
            while (!acked.get() && SystemClock.uptimeMillis() < deadline) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    return
                }
            }

            if (!acked.get()) {
                val now = SystemClock.uptimeMillis()
                if (now - lastReportElapsedMs >= throttleMs) {
                    lastReportElapsedMs = now
                    val stack = Looper.getMainLooper().thread.stackTrace
                    val rendered = buildString {
                        appendLine("Main thread stalled >${thresholdMs}ms — likely Compose recomposition loop or blocking I/O.")
                        appendLine("Stack at sample (top 60 frames):")
                        stack.take(60).forEach { appendLine("    at $it") }
                    }
                    Logger.w(throwable = null, tag = "MainThreadWatchdog") { rendered }
                }
            }

            watchdogHandler.postDelayed(this, tickIntervalMs)
        }
    }
}
