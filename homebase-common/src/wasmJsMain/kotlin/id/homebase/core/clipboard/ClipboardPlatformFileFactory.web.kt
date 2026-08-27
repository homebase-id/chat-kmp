@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package id.homebase.core.clipboard

import id.homebase.api.file.readWebFileBytes
import io.github.vinceglb.filekit.PlatformFile
import kotlin.io.encoding.Base64
import org.w3c.files.File

/**
 * The browser has no filesystem, but the wasm build's temp files are real: [readWebFileBytes]
 * serves them from the in-memory okio FakeFileSystem the upload pipeline writes to. FileKit's
 * wasm PlatformFile is a thin wrapper over a W3C File, so the bytes at [path] can be handed
 * straight back as one.
 *
 * The extension is load-bearing, not cosmetic — the send path derives the wire content-type from
 * the filename when a pending file carries no richer MIME, which is exactly the clipboard case.
 * Keep [mimeTypeForExtension] in step with `clipboardImageSuffix`, which chose the extension by
 * sniffing the pasted bytes.
 */
actual fun platformFileFromPath(path: String): PlatformFile {
    val bytes = readWebFileBytes(path)
        ?: error("No file at $path on the web filesystem")
    val name = path.substringAfterLast('/')
    val mimeType = mimeTypeForExtension(name.substringAfterLast('.', ""))
    return PlatformFile(makeJsFile(Base64.encode(bytes), name, mimeType))
}

private fun mimeTypeForExtension(extension: String): String = when (extension.lowercase()) {
    "gif" -> "image/gif"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "png" -> "image/png"
    else -> "application/octet-stream"
}

/**
 * Base64 rather than a typed array over the boundary — the same string-bridge idiom
 * [id.homebase.core.clipboard.readClipboardImage] and the ffmpeg/video bridges use, which keeps
 * the wasm side free of typed-array ownership concerns.
 */
private fun makeJsFile(base64: String, fileName: String, mimeType: String): File = js(
    """{
        var bin = atob(base64);
        var u8 = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) { u8[i] = bin.charCodeAt(i); }
        return new File([u8], fileName, { type: mimeType });
    }"""
)
