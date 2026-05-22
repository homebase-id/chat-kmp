package id.homebase.core.ui.screens.vault

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.copyTo
import io.github.vinceglb.filekit.path

actual val PlatformFile.pathCompat: String get() = path

actual suspend fun PlatformFile.copyToPath(destPath: String) {
    copyTo(PlatformFile(destPath))
}
