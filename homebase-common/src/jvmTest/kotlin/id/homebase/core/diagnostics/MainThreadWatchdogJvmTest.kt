package id.homebase.core.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
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
}
