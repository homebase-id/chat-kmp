package id.homebase.core.util

import androidx.compose.runtime.Composable
import kotlinx.browser.window
import kotlinx.io.files.Path

@Composable
actual fun getUriHandler(): FileSystemHandler {
    return object : FileSystemHandler {
        override fun openUrl(url: String, onError: (Throwable) -> Unit) {
            runCatching { window.open(url, "_blank") }.onFailure { onError(it) }
        }

        // The browser has no path-based filesystem; file open/edit/browse aren't supported on web.
        override fun editFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit) {}
        override fun openFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit) {}
        override fun openFileBrowser(file: Path, onError: (Throwable) -> Unit) {}
        override fun shareFile(file: Path, onError: (Throwable) -> Unit) {}
        override fun shareText(text: String, onError: (Throwable) -> Unit) {}
        override fun openAppStore(onError: (Throwable) -> Unit) {}
    }
}
