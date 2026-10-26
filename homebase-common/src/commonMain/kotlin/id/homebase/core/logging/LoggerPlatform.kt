package id.homebase.core.logging

import co.touchlab.kermit.LogWriter
import kotlinx.io.files.Path

/**
 * Platform shims for file-backed logging. Kept narrow on purpose: every
 * other piece of `LoggerConfig` / `LogFileExporter` is plain Kermit + Path
 * and stays in commonMain.
 *
 * - `RollingFileLogWriter` (kermit-io) has no wasmJs artifact, so the file
 *   writer is built per-platform behind [createFileLogWriter].
 * - `kotlinx.io.files.SystemFileSystem` is also platform-specific, so log-
 *   directory listing and deletion go behind [listHomebaseLogFiles] /
 *   [deleteHomebaseLogFiles].
 *
 * On wasmJs all three become safe no-ops: there is no real disk, so the
 * console-only `platformLogWriter()` Kermit installs by default carries the
 * whole logging surface.
 */
internal expect fun createFileLogWriter(
    logDirectory: Path,
    fileName: String,
    rollOnSize: Long,
    maxFiles: Int,
): LogWriter?

/**
 * Files directly inside [logDirectory] whose name starts with `homebase` and ends with `.log`
 * (the active log and its rolled siblings), in no particular order. A missing or empty
 * directory gives an empty list; it never throws for that. Blocking file I/O. Web has no disk
 * and always returns an empty list.
 */
internal expect fun listHomebaseLogFiles(logDirectory: Path): List<Path>

/**
 * Deletes every file [listHomebaseLogFiles] would list and nothing else. Returns true when
 * none remain, which includes a missing directory and web, and false when a delete failed;
 * it never throws.
 */
internal expect fun deleteHomebaseLogFiles(logDirectory: Path): Boolean

internal expect fun <T> useHomebaseLogLines(logDirectory: Path, block: (List<Sequence<String>>) -> T): T

internal expect fun writeLogExport(file: Path, text: String): Boolean
