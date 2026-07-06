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
    private val metaV2 = CrashMetadata("2.0", "debug", "JVM", "test", "2026-06-24")

    private fun crashReports(): List<String> =
        SystemFileSystem.list(Path(logDir, "crash"))
            .filter { it.name.startsWith("crash-") && it.name.endsWith(".txt") }
            .map { it.name }

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

    @Test
    fun appUpdate_clears_stale_recovery_state() {
        // Build the exact "ghost" state under v1.0 (installed in setup): two startup
        // failures + a pending report — the condition that shows the recovery screen.
        assertNull(CrashReporting.beginLaunchCheckRecovery())
        CrashReporting.writeReport("main", RuntimeException("startup-1"))
        assertNull(CrashReporting.beginLaunchCheckRecovery())
        CrashReporting.writeReport("main", RuntimeException("startup-2"))
        assertNotNull(CrashReporting.beginLaunchCheckRecovery(), "precondition: ghost recovery armed")

        // Update to v2.0 — install() runs first on the new binary and must wipe the prior
        // version's pending marker, failure counter, and reports (a 1731 report on 1738).
        CrashReporting.install(metaV2, logDir)

        assertNull(CrashReporting.pendingReport(), "old version's pending marker must be cleared")
        assertNull(
            CrashReporting.beginLaunchCheckRecovery(),
            "failure counter reset → the new version shows no ghost recovery",
        )
        assertTrue(crashReports().isEmpty(), "old version's reports must be pruned, found: ${crashReports()}")
    }

    @Test
    fun same_version_relaunch_preserves_recovery_state() {
        // A genuine startup crash loop on the SAME version must still surface — a normal
        // relaunch re-runs install() and must not nuke a legitimate pending recovery.
        assertNull(CrashReporting.beginLaunchCheckRecovery())
        CrashReporting.writeReport("main", RuntimeException("startup-1"))
        assertNull(CrashReporting.beginLaunchCheckRecovery())
        CrashReporting.writeReport("main", RuntimeException("startup-2"))

        CrashReporting.install(meta, logDir) // same version

        assertNotNull(CrashReporting.pendingReport(), "same-version relaunch keeps the pending report")
        assertNotNull(CrashReporting.beginLaunchCheckRecovery(), "same-version crash loop still shows recovery")
    }

    @Test
    fun crash_on_new_version_after_update_still_surfaces() {
        CrashReporting.writeReport("main", RuntimeException("old")) // pending under v1.0
        CrashReporting.install(metaV2, logDir)                      // update clears it
        assertNull(CrashReporting.pendingReport())

        // The new binary itself crashes on startup twice — must surface normally.
        assertNull(CrashReporting.beginLaunchCheckRecovery())
        CrashReporting.writeReport("main", RuntimeException("new-1"))
        assertNull(CrashReporting.beginLaunchCheckRecovery())
        CrashReporting.writeReport("main", RuntimeException("new-2"))
        assertNotNull(CrashReporting.beginLaunchCheckRecovery())
    }

    /**
     * The exact field incident: a report written by the OLD binary carries the OLD
     * version stamp; after an update it must be physically gone and unrecoverable, so the
     * new app can never surface it (a 1.4.1731 report appearing on 1.4.1738).
     */
    @Test
    fun old_version_report_cannot_be_surfaced_after_update() {
        // Written under v1.0 (installed in setup). The report embeds the running version.
        val oldReport = CrashReporting.writeReport(
            "Kotlin/Native", RuntimeException("no such column: fileState"),
        )
        assertNotNull(oldReport)
        val oldText = SystemFileSystem.source(oldReport).buffered().use { it.readString() }
        assertTrue("1.0" in oldText, "old report must carry the old version stamp")

        // Arm the recovery gate as in the field (a startup crash loop on the old version).
        CrashReporting.beginLaunchCheckRecovery()
        CrashReporting.beginLaunchCheckRecovery()
        assertNotNull(
            CrashReporting.beginLaunchCheckRecovery(),
            "precondition: the old version would have shown this report",
        )

        // User updates to v2.0.
        CrashReporting.install(metaV2, logDir)

        assertNull(CrashReporting.pendingReport(), "no pending report points at the old binary's crash")
        assertNull(
            SystemFileSystem.metadataOrNull(oldReport),
            "the old-version report file itself must be deleted, so it can never be shown",
        )
        assertTrue(
            crashReports().none { it == oldReport.name },
            "old report must not remain on disk: ${crashReports()}",
        )
    }
}
