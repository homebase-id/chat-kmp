package id.homebase.core.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import co.touchlab.kermit.platformLogWriter
import kotlinx.io.files.Path

object LoggerConfig {
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
            Logger.w(tag = "LoggerConfig") { "Logger already initialized" }
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
            Logger.i(tag = "LoggerConfig") { "File logging initialized at: $logDirectory" }
        } catch (e: Exception) {
            Logger.e("LoggerConfig", e, "Failed to initialize file logging")
        }

        // Set the log writers globally
        Logger.setLogWriters(logWriters)
        Logger.setMinSeverity(minimumSeverity)

        isInitialized = true
    }
}