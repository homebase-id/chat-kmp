package id.homebase.core.logging

import co.touchlab.kermit.Logger
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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
        return try {
            val files = SystemFileSystem.list(logDirectory)
                .filter { it.name.startsWith("homebase") && it.name.endsWith(".log") }
                .sortedByDescending { it.name }
            
            if (files.isEmpty()) {
                Logger.w(tag = TAG) { "No log files found in $logDirectory" }
                null
            } else {
                files.first()
            }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Failed to get log files from $logDirectory" }
            null
        }
    }
    
    /**
     * Get all log files in the directory
     */
    fun getAllLogFiles(logDirectory: Path): List<Path> {
        return try {
            SystemFileSystem.list(logDirectory)
                .filter { it.name.startsWith("homebase") && it.name.endsWith(".log") }
                .sortedByDescending { it.name }
        } catch (e: Exception) {
            Logger.e(throwable = e, tag = TAG) { "Failed to get log files from $logDirectory" }
            emptyList()
        }
    }
}