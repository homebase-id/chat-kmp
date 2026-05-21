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

internal expect fun listHomebaseLogFiles(logDirectory: Path): List<Path>

internal expect fun deleteHomebaseLogFiles(logDirectory: Path): Boolean
