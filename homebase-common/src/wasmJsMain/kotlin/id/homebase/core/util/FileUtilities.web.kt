@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package id.homebase.core.util

import androidx.compose.runtime.Composable
import id.homebase.api.file.readWebFileBytes
import kotlinx.browser.window
import kotlinx.io.files.Path
import kotlin.io.encoding.Base64

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

        override fun saveFile(
            file: Path,
            suggestedName: String,
            onSuccess: (String) -> Unit,
            onError: (Throwable) -> Unit,
        ) {
            runCatching {
                val path = file.toString()
                val bytes = readWebFileBytes(path) ?: error("No decrypted file at $path")
                triggerBrowserDownload(
                    Base64.encode(bytes),
                    detectContentTypeFromExtensionOrHint(suggestedName),
                    suggestedName,
                )
            }.onSuccess { onSuccess(DOWNLOADS_LOCATION) }.onFailure(onError)
        }
    }
}

private const val DOWNLOADS_LOCATION = "Downloads"

/*
 * The Path is into the in-memory FakeFileSystem the decrypt-on-demand flow wrote to
 * (MediaDownloadHandler), not something the browser can fetch — read the bytes back and hand them
 * to the user as a Blob object URL. Bytes cross to JS as Base64, the idiom used by
 * AudioPlayer.web.kt / HtmlVideoOverlay.web.kt.
 */
private fun triggerBrowserDownload(base64: String, mimeType: String, fileName: String): Unit = js(
    """{
        var bin = atob(base64);
        var arr = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
        var url = URL.createObjectURL(new Blob([arr], { type: mimeType }));
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
