package id.homebase.core.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import kotlinx.io.files.Path
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

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

internal actual fun readHomebaseLogLines(logDirectory: Path): List<List<String>> =
    listHomebaseLogFiles(logDirectory).sortedBy { it.name }.map { file ->
        SystemFileSystem.source(file).buffered().use { it.readString() }.lines()
    }

internal actual fun writeLogExport(file: Path, text: String): Boolean =
    try {
        SystemFileSystem.sink(file).buffered().use { it.writeString(text) }
        true
    } catch (e: Exception) {
        false
    }
