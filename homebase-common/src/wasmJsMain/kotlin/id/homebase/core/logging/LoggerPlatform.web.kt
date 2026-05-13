package id.homebase.core.logging

import co.touchlab.kermit.LogWriter
import kotlinx.io.files.Path

// No real filesystem on wasmJs — the console-only `platformLogWriter()`
// that Kermit installs by default carries the whole logging surface.
// Step 6 of the WASM pre-flight plan; an in-browser sink (e.g. accumulating
// to a Blob for user download) can come later.

internal actual fun createFileLogWriter(
    logDirectory: Path,
    fileName: String,
    rollOnSize: Long,
    maxFiles: Int,
): LogWriter? = null

internal actual fun listHomebaseLogFiles(logDirectory: Path): List<Path> = emptyList()

internal actual fun deleteHomebaseLogFiles(logDirectory: Path): Boolean = true
