package id.homebase.core.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.io.RollingFileLogWriter
import co.touchlab.kermit.io.RollingFileLogWriterConfig
import kotlinx.io.files.Path
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.FileNotFoundException
import kotlinx.io.readLine
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

// Streamed: the rolled set is up to maxFiles x rollOnSize, too big to hold as strings on a phone.
internal actual fun <T> useHomebaseLogLines(logDirectory: Path, block: (List<Sequence<String>>) -> T): T {
    val sources = listHomebaseLogFiles(logDirectory).sortedBy { it.name }.mapNotNull { file ->
        try {
            SystemFileSystem.source(file).buffered()
        } catch (_: FileNotFoundException) {
            null // rolled away between the listing and the open
        }
    }
    try {
        return block(sources.map { source -> generateSequence { source.readLine() } })
    } finally {
        sources.forEach { it.close() }
    }
}

internal actual fun writeLogExport(file: Path, text: String): Boolean =
    try {
        SystemFileSystem.sink(file).buffered().use { it.writeString(text) }
        true
    } catch (e: Exception) {
        false
    }
