@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package id.homebase.core.clipboard

import id.homebase.api.file.readWebFileBytes
import id.homebase.api.util.toJsBlob
import io.github.vinceglb.filekit.PlatformFile
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
    return PlatformFile(makeJsFile(bytes.toJsBlob(mimeType), name, mimeType))
}

private fun mimeTypeForExtension(extension: String): String = when (extension.lowercase()) {
    "gif" -> "image/gif"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "png" -> "image/png"
    else -> "application/octet-stream"
}

private fun makeJsFile(blob: JsAny, fileName: String, mimeType: String): File =
    js("new File([blob], fileName, { type: mimeType })")
