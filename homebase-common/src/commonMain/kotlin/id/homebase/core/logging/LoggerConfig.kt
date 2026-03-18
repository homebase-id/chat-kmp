package id.homebase.core.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import co.touchlab.kermit.platformLogWriter
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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

        // Add rolling file logger
        try {
            val fileLogWriter = RollingFileLogWriter(
                config =  RollingFileLogWriterConfig(
                    logFilePath = logDirectory,
                    logFileName = "homebase",
                    rollOnSize = maxFileSize,
                    maxLogFiles = maxFiles,
                ),
            )
            logWriters.add(fileLogWriter)
            Logger.i(tag = TAG) { "File logging initialized at: $logDirectory" }
        } catch (e: Exception) {
            Logger.e(TAG, e, "Failed to initialize file logging")
        }

        // Set the log writers globally
        Logger.setLogWriters(logWriters)
        Logger.setMinSeverity(minimumSeverity)

        isInitialized = true
    }

    fun purgeLogs() {
        logDirectoryPath?.let { path ->
            try {
                val files = SystemFileSystem.list(path)
                    .filter { it.name.startsWith("homebase") && it.name.endsWith(".log") }
                    .sortedByDescending { it.name }

                if (files.isEmpty()) {
                    Logger.w(tag = TAG) { "No log files found in $logDirectory" }
                    null
                } else {
                    files.forEach { file ->
                        SystemFileSystem.delete(file, mustExist = false)
                    }
                }

                // Reset initialization flag to allow re-initialization
                isInitialized = false

                // Reinitialize the logger to recreate file writers
                initialize(logDirectory = path)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "Failed to delete log files in $logDirectory" }
            }
        }
    }
}