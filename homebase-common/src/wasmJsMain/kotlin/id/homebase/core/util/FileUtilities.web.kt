package id.homebase.core.util

import androidx.compose.runtime.Composable
import kotlinx.io.files.Path

@Composable
actual fun getUriHandler(): FileSystemHandler {
    return object : FileSystemHandler {
        override fun openUrl(url: String, onError: (Throwable) -> Unit) {
            TODO("Not yet implemented")
        }

        override fun editFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit) {
            TODO("Not yet implemented")
        }

        override fun openFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit) {
            TODO("Not yet implemented")
        }

        override fun openFileBrowser(file: Path, onError: (Throwable) -> Unit) {
            TODO("Not yet implemented")
        }

        override fun shareFile(file: Path, onError: (Throwable) -> Unit) {
            // No-op on web - share not supported
        }

        override fun shareText(text: String, onError: (Throwable) -> Unit) {
            // No-op on web - share not supported
        }

        override fun openAppStore(onError: (Throwable) -> Unit) {
            TODO("Not yet implemented")
        }
    }
}
