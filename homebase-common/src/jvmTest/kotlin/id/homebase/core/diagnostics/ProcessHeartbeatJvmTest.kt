package id.homebase.core.diagnostics

import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The part of #1491's breadcrumb that only exists on disk: whether one [ProcessHeartbeat] can
 * actually read back what a *previous* one wrote. JVM-only because it needs a real temp directory;
 * the decision logic itself is covered platform-free in [MainThreadWatchdogTest].
 *
 * Each `ProcessHeartbeat` here stands in for one app process — the whole point is that the record
 * outlives the process that wrote it.
 */
class ProcessHeartbeatJvmTest {

    private val dir = Path(Files.createTempDirectory("heartbeat").toString())
    private val file = Path(dir, "heartbeat")

    private fun processStartingAt(nowEpochMs: Long) = ProcessHeartbeat(file) { nowEpochMs }

    @Test
    fun aForegroundProcessThatNeverBackgroundsIsReportedByTheNextLaunch() {
        val frozen = processStartingAt(1_000_000)
        frozen.setForeground(true)
        // …and then it is gone: force-quit mid-freeze, so it never writes anything again.

        val relaunch = processStartingAt(1_095_000)

        val message = assertNotNull(relaunch.postMortem(), "expected a post-mortem breadcrumb")
        assertTrue(message.contains("95000ms before this launch"), message)
    }

    @Test
    fun aProcessThatBackgroundedFirstIsNotReported() {
        val backgrounded = processStartingAt(1_000_000)
        backgrounded.setForeground(true)
        backgrounded.setForeground(false)

        assertNull(processStartingAt(99_000_000).postMortem())
    }

    @Test
    fun theFirstLaunchOnAFreshInstallHasNothingToReport() {
        assertNull(processStartingAt(1_000).postMortem())
    }

    @Test
    fun eachProcessReadsTheRecordOnceAndThenOwnsIt() {
        processStartingAt(1_000_000).setForeground(true)

        // The relaunch snapshots the previous record at construction, so its own beats can't
        // turn into a post-mortem about itself on the next tick.
        val relaunch = processStartingAt(1_050_000)
        relaunch.setForeground(true)
        relaunch.beat()

        assertTrue(relaunch.postMortem()!!.contains("50000ms before this launch"))
    }
}
