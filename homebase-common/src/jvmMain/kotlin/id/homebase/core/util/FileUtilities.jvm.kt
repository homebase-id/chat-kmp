package id.homebase.core.util

import androidx.compose.runtime.Composable
import co.touchlab.kermit.Logger
import kotlinx.io.files.Path
import java.awt.Desktop
import java.net.URI

private const val TAG = "getUriHandler"

@Composable
actual fun getUriHandler(): FileSystemHandler {
    return object: FileSystemHandler {
        override fun openUrl(url: String, onError: (Throwable) -> Unit) {
            try {
                openSystemBrowser(url)
            } catch (e: Exception) {
                Logger.e(TAG, e) { "Failed to open URL: ${e.message}" }
                onError(e)
            }
        }

        override fun editFile(
            file: Path,
            showChooser: Boolean,
            onError: (Throwable) -> Unit
        ) {
            TODO("Not yet implemented")
        }

        override fun openFile(
            file: Path,
            showChooser: Boolean,
            onError: (Throwable) -> Unit
        ) {
            TODO("Not yet implemented")
        }

        override fun openFileBrowser(
            file: Path,
            onError: (Throwable) -> Unit
        ) {
            TODO("Not yet implemented")
        }

        override fun shareFile(
            file: Path,
            onError: (Throwable) -> Unit
        ) {
            TODO("Not yet implemented")
        }

        override fun openAppStore(onError: (Throwable) -> Unit) {
            TODO("Not yet implemented")
        }
    }
}

private fun openSystemBrowser(url: String) {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
    ) {
        Desktop.getDesktop().browse(URI(url))
    } else {
        Logger.i(TAG) { "Java AWT Desktop not supported, trying fallback browser launch" }
        try {
            val os = System.getProperty("os.name").lowercase()
            val cmd =
                when {
                    os.contains("win") ->
                        arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
                    os.contains("mac") -> arrayOf("open", url)
                    os.contains("nix") || os.contains("nux") -> arrayOf("xdg-open", url)
                    else ->
                        throw UnsupportedOperationException(
                            "Unsupported OS for browser fallback: $os"
                        )
                }

            Logger.d(TAG) {
                "Attempting fallback browser launch with: ${cmd.joinToString(" ")}"
            }
            Runtime.getRuntime().exec(cmd)
            Logger.i(TAG) { "Fallback browser launch initiated successfully" }
        } catch (e: Exception) {
            Logger.e(TAG, e) { "Fallback browser launch failed: ${e.message}" }
            throw e
        }
    }
}