package id.homebase.core.logging

import co.touchlab.kermit.Logger
import kotlinx.io.files.Path

/**
 * Utility to export/access log files for sharing or viewing
 */
object LogFileExporter {
    private const val TAG = "LogFileExporter"

    /**
     * Get the most recent log file path
     * Returns null if no log files exist
     */
    fun getMostRecentLogFile(logDirectory: Path): Path? {
        val files = listHomebaseLogFiles(logDirectory).sortedByDescending { it.name }
        return if (files.isEmpty()) {
            Logger.w(tag = TAG) { "No log files found in $logDirectory" }
            null
        } else {
            files.first()
        }
    }

    /**
     * Get all log files in the directory
     */
    fun getAllLogFiles(logDirectory: Path): List<Path> {
        return listHomebaseLogFiles(logDirectory).sortedByDescending { it.name }
    }
}
