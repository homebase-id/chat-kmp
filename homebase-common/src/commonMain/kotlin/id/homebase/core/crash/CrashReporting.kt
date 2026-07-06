package id.homebase.core.crash

import co.touchlab.kermit.Logger
import id.homebase.core.logging.LogFileExporter
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * Dependency-free crash report persistence. Safe to call from inside a crashing
 * process: no Koin, no coroutines, no DB — only kotlinx-io file ops, each wrapped
 * so nothing here ever throws into a caller that is already crashing.
 *
 * [install] must run as the FIRST startup step on each platform (before Koin/DB),
 * with values from the static BuildConfig / platform APIs.
 */
object CrashReporting {
    private const val TAG = "CrashReporting"
    private const val PENDING_FILE = "PENDING"
    private const val LOG_TAIL_CHARS = 32_000
    private const val DEFAULT_KEEP = 5
    private const val LAUNCH_FILE = "launch_failures"
    // Records the app version that last owned the crash state, so an update can clear
    // stale reports — see [install].
    private const val VERSION_FILE = "app_version"
    // iOS only: show next-launch recovery once this many consecutive launches have
    // crashed before reaching a usable state (a startup crash loop). One silent retry.
    private const val STARTUP_FAILURE_THRESHOLD = 2

    @Volatile private var metadata: CrashMetadata? = null
    @Volatile private var logDir: Path? = null
    @Volatile private var crashDir: Path? = null

    /** @param logDir the existing homebase.log directory; crash reports go in logDir/crash. */
    fun install(metadata: CrashMetadata, logDir: Path) {
        this.metadata = metadata
        this.logDir = logDir
        val dir = Path(logDir, "crash")
        this.crashDir = dir
        runCatching { SystemFileSystem.createDirectories(dir) }
        discardStaleRecoveryStateOnVersionChange(metadata.appVersion)
    }

    /**
     * The crash report, its [PENDING_FILE] marker, and the [LAUNCH_FILE] failure counter
     * all belong to the *binary that wrote them*. After an app update they're no longer
     * actionable: a crash fixed by the update would otherwise be replayed on the new
     * version's first launch (the recovery screen shows the OLD version's report — e.g. a
     * 1.4.1731 crash surfacing on 1.4.1738), and the stale failure counter can trip the
     * recovery gate even though the new binary never crashed.
     *
     * So on the first launch where [current] differs from the stored version, clear the
     * pending marker, reset the failure counter, and drop every old `crash-*.txt`. A crash
     * on the *new* version is written after this runs ([install] is the first startup
     * step), so it is unaffected and surfaces normally. First install ever (no stored
     * version) just records it without clearing.
     */
    private fun discardStaleRecoveryStateOnVersionChange(current: String) {
        val dir = crashDir ?: return
        runCatching {
            val versionFile = Path(dir, VERSION_FILE)
            val previous = readSmallFile(versionFile)
            if (previous == current) return
            if (previous != null) {
                clearPending()
                writeLaunchCount(0)
                pruneOld(keep = 0)
                runCatching { Logger.i(tag = TAG) { "App version changed ($previous -> $current); cleared stale crash recovery state" } }
            }
            writeSmallFile(versionFile, current)
        }
    }

    /**
     * iOS launch entry. Returns the pending crash report to recover from ONLY when the
     * app is stuck failing to start — at least [STARTUP_FAILURE_THRESHOLD] consecutive
     * launches crashed before [markStartupComplete]. Otherwise records this launch
     * attempt and returns null so startup proceeds normally. A mid-session crash never
     * trips this: startup completed, so the counter was reset.
     */
    fun beginLaunchCheckRecovery(): Path? {
        val dir = crashDir ?: return null
        val failures = readLaunchCount()
        val report = pendingReport()
        if (failures >= STARTUP_FAILURE_THRESHOLD && report != null) return report
        writeLaunchCount(failures + 1)
        return null
    }

    /** App reached a usable state — reset the consecutive-startup-failure counter. */
    fun markStartupComplete() {
        writeLaunchCount(0)
    }

    private fun writeLaunchCount(n: Int) {
        val dir = crashDir ?: return
        runCatching {
            SystemFileSystem.sink(Path(dir, LAUNCH_FILE)).buffered().use { it.writeString(n.toString()) }
        }
    }

    /** Read a small single-line marker file (version stamp); null if absent/empty/unreadable. */
    private fun readSmallFile(path: Path): String? = try {
        if (SystemFileSystem.metadataOrNull(path) == null) null
        else SystemFileSystem.source(path).buffered().use { it.readString() }.trim().ifEmpty { null }
    } catch (t: Throwable) {
        null
    }

    private fun writeSmallFile(path: Path, text: String) {
        runCatching { SystemFileSystem.sink(path).buffered().use { it.writeString(text) } }
    }

    private fun readLaunchCount(): Int {
        val dir = crashDir ?: return 0
        return try {
            val f = Path(dir, LAUNCH_FILE)
            if (SystemFileSystem.metadataOrNull(f) == null) 0
            else SystemFileSystem.source(f).buffered().use { it.readString() }.trim().toIntOrNull() ?: 0
        } catch (t: Throwable) {
            0
        }
    }

    fun writeReport(thread: String, throwable: Throwable): Path? = writeInternal(
        thread = thread,
        exceptionLine = buildString {
            append(throwable::class.qualifiedName ?: throwable::class.simpleName ?: "Throwable")
            append(": ")
            append(throwable.message ?: "")
        },
        stackText = throwable.stackTraceToString(),
    )

    /** For the iOS ObjC channel, where there is no Kotlin [Throwable]. */
    fun writeReportRaw(thread: String, name: String, reason: String, stack: List<String>): Path? = writeInternal(
        thread = thread,
        exceptionLine = if (reason.isBlank()) name else "$name: $reason",
        stackText = stack.joinToString("\n"),
    )

    private fun writeInternal(thread: String, exceptionLine: String, stackText: String): Path? {
        val dir = crashDir ?: return null
        return try {
            runCatching { SystemFileSystem.createDirectories(dir) }
            val now = Clock.System.now()
            val report = CrashReport.render(
                metadata = metadata,
                timeIso = now.toString(),
                thread = thread,
                exceptionLine = exceptionLine,
                stackText = stackText,
                logTail = readLogTail(),
            )
            val base = now.toEpochMilliseconds()
            var file = Path(dir, "crash-$base.txt")
            var suffix = 1
            while (SystemFileSystem.metadataOrNull(file) != null) {
                file = Path(dir, "crash-$base-${suffix++}.txt")
            }
            SystemFileSystem.sink(file).buffered().use { it.writeString(report) }
            runCatching {
                SystemFileSystem.sink(Path(dir, PENDING_FILE)).buffered().use { it.writeString(file.name) }
            }
            runCatching { pruneOld() }
            file
        } catch (t: Throwable) {
            runCatching { Logger.e(tag = TAG) { "Failed to write crash report: ${t.message}" } }
            null
        }
    }

    private fun readLogTail(): String? {
        val ld = logDir ?: return null
        return try {
            val logFile = LogFileExporter.getMostRecentLogFile(ld) ?: return null
            val content = SystemFileSystem.source(logFile).buffered().use { it.readString() }
            if (content.length > LOG_TAIL_CHARS) content.takeLast(LOG_TAIL_CHARS) else content
        } catch (t: Throwable) {
            null
        }
    }

    fun pendingReport(): Path? {
        val dir = crashDir ?: return null
        return try {
            val pending = Path(dir, PENDING_FILE)
            if (SystemFileSystem.metadataOrNull(pending) == null) return null
            val name = SystemFileSystem.source(pending).buffered().use { it.readString() }.trim()
            if (name.isEmpty()) return null
            val report = Path(dir, name)
            if (SystemFileSystem.metadataOrNull(report) == null) null else report
        } catch (t: Throwable) {
            null
        }
    }

    fun clearPending() {
        val dir = crashDir ?: return
        runCatching { SystemFileSystem.delete(Path(dir, PENDING_FILE), mustExist = false) }
    }

    fun pruneOld(keep: Int = DEFAULT_KEEP) {
        val dir = crashDir ?: return
        runCatching {
            SystemFileSystem.list(dir)
                .filter { it.name.startsWith("crash-") && it.name.endsWith(".txt") }
                .sortedByDescending { it.name }
                .drop(keep)
                .forEach { runCatching { SystemFileSystem.delete(it, mustExist = false) } }
        }
    }
}
