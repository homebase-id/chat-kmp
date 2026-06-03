package id.homebase.core.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import kotlinx.io.files.Path

object LoggerConfig {
    private const val TAG = "LoggerConfig"
    private var isInitialized = false
    private var logDirectoryPath: Path? = null

    /**
     * Get the current log directory path
     */
    val logDirectory: Path?
        get() = logDirectoryPath

    /**
     * Initialize Kermit with file logging
     * @param logDirectory Directory where log files will be stored
     * @param maxFileSize Maximum size of each log file in bytes (default: 1MB)
     * @param maxFiles Maximum number of log files to keep (default: 5)
     */
    fun initialize(
        logDirectory: Path,
        maxFileSize: Long = 1024 * 1024 * 10, // 10MB
        maxFiles: Int = 5,
        minimumSeverity: Severity = Severity.Verbose
    ) {
        if (isInitialized) {
            Logger.w(tag = TAG) { "Logger already initialized" }
            return
        }

        logDirectoryPath = logDirectory
        val logWriters = mutableListOf<LogWriter>()

        // Add platform-specific console logger (Logcat on Android, NSLog on iOS, etc.)
        logWriters.add(platformLogWriter())

        // Mirror logs into the platform crash reporter: Info+ become Crashlytics
        // breadcrumbs (the recent log trail attached to the next crash report)
        // and Error+ throwables are recorded as non-fatals. No-op on Desktop/Web.
        logWriters.add(CrashlyticsLogWriter())

        // Add rolling file logger
        try {
            val fileLogWriter = createFileLogWriter(
                logDirectory = logDirectory,
                fileName = "homebase",
                rollOnSize = maxFileSize,
                maxFiles = maxFiles,
            )
            if (fileLogWriter != null) {
                logWriters.add(fileLogWriter)
                Logger.i(tag = TAG) { "File logging initialized at: $logDirectory" }
            } else {
                Logger.i(tag = TAG) { "File logging unavailable on this platform — console only" }
            }
        } catch (e: Exception) {
            Logger.e(TAG, e, "Failed to initialize file logging")
        }

        // Set the log writers globally
        Logger.setLogWriters(logWriters)
        Logger.setMinSeverity(minimumSeverity)

        isInitialized = true
    }

    /**
     * Delete all `homebase*.log` files in the log directory and restart file logging.
     *
     * Returns true if every matching file was deleted, false otherwise.
     *
     * On Windows the active log file is held open with an exclusive lock by
     * the file log writer, so we drop it from the Logger's writer list before
     * attempting to delete; the file handle is released when the writer is GC'd.
     * Callers that care about the user-visible outcome (e.g. a "Clear log" button)
     * should use the return value rather than assume success.
     */
    fun purgeLogs(): Boolean {
        val path = logDirectoryPath ?: return false

        // Release the file writer so the active log file is no longer locked.
        Logger.setLogWriters(listOf(platformLogWriter()))
        isInitialized = false

        val deleted = deleteHomebaseLogFiles(path)
        if (!deleted) {
            Logger.e(tag = TAG) { "Failed to delete log files in $path" }
        }

        // Restore file logging regardless of outcome — we don't want the rest of the
        // session running with console-only logging if the delete failed.
        initialize(logDirectory = path)

        return deleted
    }
}
