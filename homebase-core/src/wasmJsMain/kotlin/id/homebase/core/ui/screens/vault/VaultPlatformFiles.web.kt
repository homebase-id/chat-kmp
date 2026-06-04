package id.homebase.core.ui.screens.vault

import io.github.vinceglb.filekit.PlatformFile

// The browser has no filesystem paths; vault uploads are not supported on web yet.
// These actuals exist only so the shared VaultViewModel compiles for wasmJs.

actual val PlatformFile.pathCompat: String
    get() = error("File paths are not available on web")

actual suspend fun PlatformFile.copyToPath(destPath: String) {
    error("Copying files by path is not supported on web")
}
