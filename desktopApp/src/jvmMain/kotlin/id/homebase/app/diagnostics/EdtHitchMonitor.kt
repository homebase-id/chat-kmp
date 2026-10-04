package id.homebase.app.diagnostics

import co.touchlab.kermit.Logger
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Logs sub-second EDT stalls, which MainThreadWatchdog (4 s threshold, 30 s throttle) never reports. */
class EdtHitchMonitor(
    private val thresholdMs: Long = 500,
    private val pollMs: Long = 250,
    private val throttleMs: Long = 5_000,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val post: (Runnable) -> Unit = EventQueue::invokeLater,
    private val captureStack: () -> String? = ::captureEdtStack,
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
                Thread.sleep(pollMs)
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
        post { acked.countDown() }
        if (acked.await(thresholdMs, TimeUnit.MILLISECONDS)) return
        // Snapshot while the EDT is still stuck; after the ack it shows idle frames.
        val stack = captureStack()
        acked.await()
        report(postedAt, nowMs() - postedAt, stack)
    }

    private fun report(postedAt: Long, hitchMs: Long, stack: String?) {
        val now = nowMs()
        val firstSinceFocus = unreportedFocusGain
        val throttled = lastLogMs?.let { now - it < throttleMs } ?: false
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

private fun captureEdtStack(): String? {
    val edt = Thread.getAllStackTraces().entries.firstOrNull { it.key.name.startsWith("AWT-EventQueue") }
        ?: return null
    return buildString {
        appendLine("    [thread: ${edt.key.name}]")
        edt.value.take(60).forEach { appendLine("    at $it") }
    }
}
