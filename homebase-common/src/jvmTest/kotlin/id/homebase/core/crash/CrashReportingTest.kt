package id.homebase.core.crash

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashReportingTest {
    private lateinit var logDir: Path

    private val meta = CrashMetadata("1.0", "debug", "JVM", "test", "2026-06-10")

    @BeforeTest
    fun setup() {
        // Unique per-run dir name (no Math.random in commonTest — use nanoTime).
        logDir = Path(SystemTemporaryDirectory, "crashtest-${System.nanoTime()}")
        SystemFileSystem.createDirectories(logDir)
        CrashReporting.install(meta, logDir)
    }

    @AfterTest
    fun teardown() {
        runCatching {
            val crashDir = Path(logDir, "crash")
            SystemFileSystem.list(crashDir).forEach { SystemFileSystem.delete(it, mustExist = false) }
            SystemFileSystem.delete(crashDir, mustExist = false)
            SystemFileSystem.delete(logDir, mustExist = false)
        }
    }

    @Test
    fun writeReport_writes_file_and_sets_pending() {
        val path = CrashReporting.writeReport("main", IllegalStateException("boom"))
        assertNotNull(path)
        val text = SystemFileSystem.source(path).buffered().use { it.readString() }
        assertTrue("boom" in text)
        assertTrue("Stack trace" in text)
        val pending = CrashReporting.pendingReport()
        assertEquals(path.name, pending?.name)
    }

    @Test
    fun clearPending_removes_marker_keeps_report() {
        val path = CrashReporting.writeReport("main", RuntimeException("x"))
        assertNotNull(CrashReporting.pendingReport())
        CrashReporting.clearPending()
        assertNull(CrashReporting.pendingReport())
        // report file itself still exists
        assertTrue(SystemFileSystem.metadataOrNull(path!!) != null)
    }

    @Test
    fun pruneOld_keeps_newest_n() {
        repeat(8) { CrashReporting.writeReport("main", RuntimeException("e$it")) }
        CrashReporting.pruneOld(keep = 5)
        val crashDir = Path(logDir, "crash")
        val reports = SystemFileSystem.list(crashDir)
            .filter { it.name.startsWith("crash-") && it.name.endsWith(".txt") }
        assertEquals(5, reports.size)
    }

    @Test
    fun writeReportRaw_renders_objc_style() {
        val path = CrashReporting.writeReportRaw("ObjC", "NSRangeException", "out of bounds", listOf("frame0", "frame1"))
        assertNotNull(path)
        val text = SystemFileSystem.source(path).buffered().use { it.readString() }
        assertTrue("NSRangeException: out of bounds" in text)
        assertTrue("frame0" in text)
    }

    @Test
    fun stuck_startup_shows_recovery_after_two_failures() {
        // Launch 1: begin (no recovery), then crash before startup completes.
        assertNull(CrashReporting.beginLaunchCheckRecovery())
        CrashReporting.writeReport("main", RuntimeException("startup-1"))
        // Launch 2: silent retry (no recovery), crash again.
        assertNull(CrashReporting.beginLaunchCheckRecovery())
        CrashReporting.writeReport("main", RuntimeException("startup-2"))
        // Launch 3: two consecutive startup failures -> recovery shows.
        assertNotNull(CrashReporting.beginLaunchCheckRecovery())
    }

    @Test
    fun mid_session_crash_does_not_show_recovery() {
        // Launch: startup completes, then a mid-session crash.
        assertNull(CrashReporting.beginLaunchCheckRecovery())
        CrashReporting.markStartupComplete()
        CrashReporting.writeReport("main", RuntimeException("mid-session"))
        // Next launch: report exists but the failure counter was reset -> no recovery.
        assertNull(CrashReporting.beginLaunchCheckRecovery())
    }

    @Test
    fun successful_start_resets_failure_count() {
        assertNull(CrashReporting.beginLaunchCheckRecovery())
        CrashReporting.writeReport("main", RuntimeException("startup-1"))
        assertNull(CrashReporting.beginLaunchCheckRecovery())
        CrashReporting.markStartupComplete() // recovered on the retry
        // Even though a report exists, the reset counter means no recovery next launch.
        assertNull(CrashReporting.beginLaunchCheckRecovery())
    }
}
