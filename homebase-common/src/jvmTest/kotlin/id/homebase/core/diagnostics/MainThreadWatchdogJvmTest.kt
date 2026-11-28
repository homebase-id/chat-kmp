package id.homebase.core.diagnostics

import co.touchlab.kermit.Severity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression test for the pre-#941 behavior: a genuinely unresponsive `mainDispatcher` must still
 * produce a [StallKind.MainThreadBlock] breadcrumb. JVM-only because it needs
 * [java.util.concurrent.Executors] to build a deterministically wedged dispatcher (a real thread
 * kept permanently busy, so anything posted to it never runs) — the closest practical stand-in
 * for "the UI thread is blocked" without depending on a platform UI toolkit.
 */
class MainThreadWatchdogJvmTest {

    @Test
    fun detectsANonRespondingMainDispatcher() = runBlocking {
        val logged = mutableListOf<String>()
        val wedgedThreadPool = Executors.newSingleThreadExecutor()
        val wedgedDispatcher = wedgedThreadPool.asCoroutineDispatcher()
        wedgedThreadPool.submit {
            while (!Thread.currentThread().isInterrupted) {
                // busy-spin so nothing else posted to this dispatcher ever runs
            }
        }

        val watchdog = MainThreadWatchdog(
            thresholdMs = 50,
            tickIntervalMs = 20,
            throttleMs = 5_000,
            mainDispatcher = wedgedDispatcher,
            workDispatcher = Dispatchers.Default,
            log = { logged += it },
        )

        try {
            watchdog.start()
            val deadline = System.nanoTime() + 2_000_000_000L
            while (logged.isEmpty() && System.nanoTime() < deadline) {
                delay(20)
            }
        } finally {
            watchdog.stop()
            wedgedThreadPool.shutdownNow()
        }

        assertTrue(logged.isNotEmpty(), "expected a stalled-main-thread breadcrumb")
        assertTrue(logged.first().contains("Main/UI thread stalled"))
    }

    private class Harness {
        val stalls = CopyOnWriteArrayList<String>()
        val lifecycle = CopyOnWriteArrayList<Triple<Severity, String, Throwable?>>()
        val wedgedPool = Executors.newSingleThreadExecutor()
        val wedged = wedgedPool.asCoroutineDispatcher()

        fun wedgeMain() {
            wedgedPool.submit {
                while (!Thread.currentThread().isInterrupted) {
                    // busy-spin so nothing posted to this dispatcher ever runs
                }
            }
        }

        fun watchdog(
            main: CoroutineDispatcher = wedged,
            aliveLogIntervalMs: Long = 600_000,
            captureStack: () -> String? = { null },
        ) = MainThreadWatchdog(
            thresholdMs = 50,
            tickIntervalMs = 20,
            throttleMs = 5_000,
            mainDispatcher = main,
            log = { stalls += it },
            aliveLogIntervalMs = aliveLogIntervalMs,
            lifecycleLog = { sev, msg, t -> lifecycle += Triple(sev, msg, t) },
            captureStack = captureStack,
            useLivenessProbe = false,
        )

        fun awaitUntil(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        }

        fun close() {
            wedgedPool.shutdownNow()
        }
    }

    @Test
    fun coroutineLoopAloneReportsAPermanentMainBlock_onItsOwnThread() {
        val h = Harness()
        h.wedgeMain()
        val watchdog = h.watchdog()
        try {
            watchdog.start()
            h.awaitUntil { h.stalls.isNotEmpty() }
        } finally {
            watchdog.stop()
            h.close()
        }

        assertTrue(h.stalls.isNotEmpty(), "loop never reported; lifecycle=${h.lifecycle}")
        assertTrue(h.stalls.first().startsWith("Main/UI thread stalled"), h.stalls.first())
        val started = h.lifecycle.first { it.second.startsWith("Watchdog loop started") }.second
        assertTrue(started.contains("thread=MainThreadWatchdog"), started)
    }

    @Test
    fun anExceptionInOneIteration_isLoggedAndDoesNotKillTheLoop() {
        val h = Harness()
        h.wedgeMain()
        val calls = AtomicInteger()
        val watchdog = h.watchdog(captureStack = {
            if (calls.incrementAndGet() == 1) error("boom") else null
        })
        try {
            watchdog.start()
            h.awaitUntil { h.stalls.isNotEmpty() }
        } finally {
            watchdog.stop()
            h.close()
        }

        val failure = h.lifecycle.firstOrNull { it.second.startsWith("Watchdog loop iteration failed") }
        assertTrue(
            failure != null && failure.first == Severity.Error && failure.third?.message == "boom",
            "lifecycle=${h.lifecycle}",
        )
        assertTrue(h.stalls.isNotEmpty(), "loop did not survive the exception")
        assertTrue(h.lifecycle.none { it.second.startsWith("Watchdog loop died") })
    }

    @Test
    fun logsAliveHeartbeat_andStopWithReason() {
        val h = Harness()
        val main = Executors.newSingleThreadExecutor()
        val watchdog = h.watchdog(main = main.asCoroutineDispatcher(), aliveLogIntervalMs = 30)
        try {
            watchdog.start()
            h.awaitUntil { h.lifecycle.any { it.second.startsWith("Watchdog loop alive") } }
            watchdog.stop()
            h.awaitUntil { h.lifecycle.any { it.second.startsWith("Watchdog loop stopped") } }
        } finally {
            main.shutdownNow()
            h.close()
        }

        assertTrue(h.lifecycle.any { it.second.startsWith("Watchdog loop alive") }, "lifecycle=${h.lifecycle}")
        val stopped = h.lifecycle.first { it.second.startsWith("Watchdog loop stopped") }.second
        assertTrue(stopped.contains("stop() called"), stopped)
        assertTrue(h.stalls.isEmpty(), "healthy main must not report: ${h.stalls}")
    }
}
