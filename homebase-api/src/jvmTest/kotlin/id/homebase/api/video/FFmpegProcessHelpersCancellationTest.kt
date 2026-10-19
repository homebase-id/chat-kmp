package id.homebase.api.video

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

class FFmpegProcessHelpersCancellationTest {

    private fun longSleep(): Pair<String, List<String>> {
        assumeTrue(!System.getProperty("os.name").lowercase().contains("windows"))
        val marker = "9${UUID.randomUUID().toString().filter { it.isDigit() }.take(8).padEnd(8, '1')}"
        return marker to listOf("sleep", marker)
    }

    private fun live(marker: String): Boolean =
        ProcessHandle.allProcesses().anyMatch { h ->
            h.isAlive && h.info().arguments().map { a -> a.contains(marker) }.orElse(false)
        }

    private fun awaitStarted(marker: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!live(marker) && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue(live(marker), "helper never started the process")
    }

    private fun awaitGone(marker: String): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (live(marker) && System.currentTimeMillis() < deadline) Thread.sleep(25)
        return !live(marker)
    }

    private fun cancelLeavesNoProcess(run: suspend (List<String>) -> Unit) = runBlocking {
        val (marker, command) = longSleep()
        val job = launch(Dispatchers.IO) { run(command) }
        try {
            awaitStarted(marker)
            job.cancel()
            assertTrue(awaitGone(marker), "process still alive after coroutine cancellation")
        } finally {
            ProcessHandle.allProcesses()
                .filter { h -> h.info().arguments().map { a -> a.contains(marker) }.orElse(false) }
                .forEach { it.destroyForcibly() }
        }
    }

    @Test
    fun `cancelling runProcess kills the process`() = cancelLeavesNoProcess { FFmpegUtils.runProcess(it) }

    @Test
    fun `cancelling runProcessWithOutput kills the process`() =
        cancelLeavesNoProcess { FFmpegUtils.runProcessWithOutput(it) }

    @Test
    fun `cancelling runProcessWithLogs kills the process`() =
        cancelLeavesNoProcess { FFmpegUtils.runProcessWithLogs(it, 1000L, null) }

    @Test
    fun `runProcessWithLogs reports progress and exit code`() = runBlocking {
        assumeTrue(!System.getProperty("os.name").lowercase().contains("windows"))
        val seen = mutableListOf<Float>()
        val result = withTimeout(10_000) {
            async(Dispatchers.IO) {
                FFmpegUtils.runProcessWithLogs(
                    listOf("sh", "-c", "echo out_time_ms=500; echo out_time_ms=1000; exit 3"),
                    1000L,
                    onProgress = { seen += it },
                )
            }.await()
        }
        assertEquals(3, result.exitCode)
        assertEquals(listOf(0.5f, 1f), seen)
        assertFalse(result.output.isEmpty())
    }
}
