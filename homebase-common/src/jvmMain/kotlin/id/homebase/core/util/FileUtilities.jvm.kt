package id.homebase.core.util

import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger
import kotlinx.io.files.Path
import java.awt.Desktop

private const val TAG = "getUriHandler"

@Composable
actual fun getUriHandler(): FileSystemHandler {
    return object : FileSystemHandler {
        override fun openUrl(url: String, onError: (Throwable) -> Unit) {
            try {
                BrowserUtils.openSystemBrowser(url, enableClipboardFallback = false)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "Failed to open URL: ${e.message}" }
                onError(e)
            }
        }

        override fun editFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit) {
            TODO("Not yet implemented")
        }

        override fun openFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit) {
            try {
                val javaFile = java.io.File(file.toString())
                if (Desktop.isDesktopSupported()) {
                    val desktop = Desktop.getDesktop()
                    if (desktop.isSupported(Desktop.Action.OPEN)) {
                        desktop.open(javaFile)
                    } else {
                        onError(Exception("Desktop open action not supported"))
                    }
                } else {
                    onError(Exception("Desktop not supported"))
                }
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "Failed to open file: ${e.message}" }
                onError(e)
            }
        }

        override fun openFileBrowser(file: Path, onError: (Throwable) -> Unit) {
            try {
                val javaFile = java.io.File(file.toString())
                if (Desktop.isDesktopSupported()) {
                    val desktop = Desktop.getDesktop()

                    // Try to open the parent directory and select the file
                    if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                        desktop.browseFileDirectory(javaFile)
                    } else if (desktop.isSupported(Desktop.Action.OPEN)) {
                        // Fallback: open the parent directory
                        val parentDir = javaFile.parentFile
                        if (parentDir != null && parentDir.exists()) {
                            desktop.open(parentDir)
                        } else {
                            onError(Exception("Parent directory not found"))
                        }
                    } else {
                        onError(Exception("Desktop browse/open action not supported"))
                    }
                } else {
                    onError(Exception("Desktop not supported"))
                }
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "Failed to open file browser: ${e.message}" }
                onError(e)
            }
        }

        override fun shareFile(file: Path, onError: (Throwable) -> Unit) {
            // No-op on desktop - share not supported
        }

        override fun shareText(text: String, onError: (Throwable) -> Unit) {
            // No-op on desktop - share not supported
        }

        override fun openAppStore(onError: (Throwable) -> Unit) {
            TODO("Not yet implemented")
        }
    }
}
