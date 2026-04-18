package id.homebase.core.logging

import co.touchlab.kermit.Logger
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the Clear-log button silently no-opping on Windows.
 *
 * Bug (homebase.log 2026-04-18 12:09:01Z): `LoggerConfig.purgeLogs()` called
 * `SystemFileSystem.delete()` without first releasing Kermit's `RollingFileLogWriter`,
 * so on Windows the active `homebase.log` stayed locked, the delete threw
 * `IOException: Deletion failed`, the catch swallowed it, and `purgeLogs` returned
 * normally — leaving `HomeViewModel.clearLogFile` to log `"Log cleared by user"` as
 * if the operation had succeeded. The user was told the log was cleared when nothing
 * had happened.
 *
 * `LoggerConfig` is a singleton `object`, so tests share state across runs. The suite
 * is written as a single sequential test to avoid cross-test contamination without
 * having to expose a reset hook from production code.
 */
class LoggerConfigTest {

    private var tempDir: java.io.File? = null

    @AfterTest
    fun cleanup() {
        tempDir?.deleteRecursively()
    }

    private fun logFilesIn(path: Path): List<String> =
        SystemFileSystem.list(path)
            .map { it.name }
            .filter { it.startsWith("homebase") && it.endsWith(".log") }
            .sorted()

    @Test
    fun purgeLogs_deletes_reports_true_and_keeps_logger_usable() {
        val dir = Files.createTempDirectory("homebase-logger-test-").toFile()
        tempDir = dir
        val path = Path(dir.absolutePath)

        // Pre-seed rotated logs to stand in for a real history.
        listOf("homebase.log", "homebase.1.log", "homebase.2.log").forEach { name ->
            java.io.File(dir, name).writeText("old content $name")
        }
        assertEquals(3, logFilesIn(path).size, "pre-seed should place three log files")

        // `initialize` is idempotent after the first call (guarded by `isInitialized`),
        // so if a prior test in the same JVM already pointed LoggerConfig at a different
        // directory this no-ops. Call `purgeLogs` once first to force the internal state
        // to a known value (it resets `isInitialized` and re-initializes to *this* dir).
        LoggerConfig.initialize(logDirectory = path, maxFileSize = 1024L, maxFiles = 3)
        if (LoggerConfig.logDirectory != path) {
            // Previous-test contamination: force re-init to the dir we want.
            LoggerConfig.purgeLogs()
            LoggerConfig.initialize(logDirectory = path, maxFileSize = 1024L, maxFiles = 3)
        }

        // Pre-seed files were written directly to disk and bypass the logger, so they
        // still exist; the one we care about is that purgeLogs will remove them.
        val ok = LoggerConfig.purgeLogs()
        assertTrue(ok, "purgeLogs should report success on a writable directory")

        val survivors = logFilesIn(path)
        val stalePreSeeded = survivors.filter { it in setOf("homebase.1.log", "homebase.2.log") }
        assertEquals(emptyList(), stalePreSeeded, "rotated pre-seed files must be deleted")

        // After purge + re-init, the logger should still be usable — no throw — and the
        // log directory pointer should still resolve.
        assertEquals(path, LoggerConfig.logDirectory, "re-init should keep pointing at the same dir")
        Logger.i(tag = "LoggerConfigTest") { "post-purge line" }
    }
}
