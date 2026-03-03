package id.homebase.core.util

import androidx.compose.runtime.Composable
import kotlinx.io.files.Path

@Composable
expect fun getUriHandler(): FileSystemHandler

interface FileSystemHandler {
    fun openUrl(url: String, onError: (Throwable) -> Unit = {})
    fun editFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit = {})
    fun openFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit = {})
    fun openFileBrowser(file: Path, onError: (Throwable) -> Unit = {})
    fun shareFile(file: Path, onError: (Throwable) -> Unit = {})
    fun shareText(text: String, onError: (Throwable) -> Unit = {})
    fun openAppStore(onError: (Throwable) -> Unit = {})
}
