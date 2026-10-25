package id.homebase.core.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal actual fun createFileLogWriter(
    logDirectory: Path,
    fileName: String,
    rollOnSize: Long,
    maxFiles: Int,
): LogWriter? = RollingFileLogWriter(
    config = RollingFileLogWriterConfig(
        logFilePath = logDirectory,
        logFileName = fileName,
        rollOnSize = rollOnSize,
        maxLogFiles = maxFiles,
    ),
)

internal actual fun listHomebaseLogFiles(logDirectory: Path): List<Path> =
    SystemFileSystem.list(logDirectory)
        .filter { it.name.startsWith("homebase") && it.name.endsWith(".log") }

internal actual fun deleteHomebaseLogFiles(logDirectory: Path): Boolean =
    try {
        listHomebaseLogFiles(logDirectory).forEach {
            SystemFileSystem.delete(it, mustExist = false)
        }
        true
    } catch (e: Exception) {
        false
    }
