package id.homebase.app.diagnostics

import co.touchlab.kermit.Logger
import id.homebase.core.diagnostics.captureMainThreadStackTrace
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Logs sub-second EDT stalls, which MainThreadWatchdog (4 s threshold, 30 s throttle) never reports. */
class EdtHitchMonitor(
    private val log: (String) -> Unit = { Logger.w(tag = "EdtHitchMonitor") { it } },
) {
    @Volatile private var lastFocusGainMs: Long? = null
    @Volatile private var unreportedFocusGain = false
    private var lastLogMs: Long? = null

    fun start() {
        current = this
        thread(name = "EdtHitchMonitor", isDaemon = true) {
            while (true) {
                probeOnce()
                Thread.sleep(POLL_MS)
            }
        }
    }

    private fun markFocusGained() {
        lastFocusGainMs = nowMs()
        unreportedFocusGain = true
    }

    private fun probeOnce() {
        val acked = CountDownLatch(1)
        val postedAt = nowMs()
        EventQueue.invokeLater { acked.countDown() }
        if (acked.await(THRESHOLD_MS, TimeUnit.MILLISECONDS)) return
        // Snapshot while the EDT is still stuck; after the ack it shows idle frames.
        val stack = captureMainThreadStackTrace()
        acked.await()
        report(postedAt, nowMs() - postedAt, stack)
    }

    private fun report(postedAt: Long, hitchMs: Long, stack: String?) {
        val now = nowMs()
        val firstSinceFocus = unreportedFocusGain
        val throttled = lastLogMs?.let { now - it < THROTTLE_MS } ?: false
        if (throttled && !firstSinceFocus) return
        unreportedFocusGain = false
        lastLogMs = now
        val focus = lastFocusGainMs?.let { "${postedAt - it}ms after focus gain" } ?: "no focus gain yet"
        log("EDT hitch: ${hitchMs}ms ($focus)\n${stack ?: "    [EDT stack unavailable]"}")
    }

    companion object {
        @Volatile private var current: EdtHitchMonitor? = null

        fun onFocusGained() {
            current?.markFocusGained()
        }
    }
}

private const val THRESHOLD_MS = 500L
private const val POLL_MS = 250L
private const val THROTTLE_MS = 5_000L

private fun nowMs(): Long = System.nanoTime() / 1_000_000
