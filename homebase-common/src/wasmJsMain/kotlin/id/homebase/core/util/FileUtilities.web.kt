package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import id.homebase.api.file.WebFileOperationsProvider
import id.homebase.api.file.detectContentTypeFromExtensionOrHint
import id.homebase.api.file.readWebFileBytes
import id.homebase.api.util.toBlobObjectUrl
import kotlinx.browser.window
import kotlinx.io.files.Path

@Composable
actual fun getUriHandler(): FileSystemHandler {
    val fileOps = remember { WebFileOperationsProvider() }
    val saver = remember { FileSaver(WebAlbumSaver, fileOps) }
    return object : FileSystemHandler {
        override fun openUrl(url: String, onError: (Throwable) -> Unit) {
            runCatching {
                // window.open resolves a scheme-less URL against the app's own origin instead of failing.
                require(hasUrlScheme(url)) { "No scheme in URL: $url" }
                window.open(url, "_blank") ?: error("The browser blocked opening $url")
            }.onFailure(onError)
        }

        // The browser has no path-based filesystem; file edit/browse aren't supported on web.
        override fun editFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit) {}
        override fun openFile(file: Path, showChooser: Boolean, onError: (Throwable) -> Unit) {
            runCatching { downloadWebFile(file, file.name) }.onFailure(onError)
        }
        override fun openFileBrowser(file: Path, onError: (Throwable) -> Unit) {}
        override fun shareFile(file: Path, onError: (Throwable) -> Unit) {}
        override fun shareText(text: String, onError: (Throwable) -> Unit) {}
        override fun openAppStore(onError: (Throwable) -> Unit) {}

        override fun saveFile(
            file: Path,
            suggestedName: String,
            deleteAfter: Boolean,
            onSuccess: (String) -> Unit,
            onError: (Throwable) -> Unit,
        ) = saver.save(file, suggestedName, deleteAfter, onSuccess, onError)

        override fun offerRefused(refused: AlbumSaveResult.Refused, onError: (Throwable) -> Unit) =
            saver.offerRefused(refused, onError)
    }
}



internal fun downloadWebFile(file: Path, fileName: String) {
    val path = file.toString()
    val bytes = readWebFileBytes(path) ?: error("No decrypted file at $path")
    val mimeType = detectContentTypeFromExtensionOrHint(fileName)
    triggerBrowserDownload(bytes.toBlobObjectUrl(mimeType), fileName)
}

/*
 * The Path is into the in-memory FakeFileSystem the decrypt-on-demand flow wrote to
 * (MediaDownloadHandler), not something the browser can fetch — read the bytes back and hand them
 * to the user as a Blob object URL.
 */
internal fun triggerBrowserDownload(url: String, fileName: String): Unit = js(
    """{
        var a = document.createElement('a');
        a.href = url;
        a.download = fileName;
        a.style.display = 'none';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        // Revoking in the same task cancels the download in Safari and Firefox.
        setTimeout(function () { URL.revokeObjectURL(url); }, 60000);
    }"""
)
