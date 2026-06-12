@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package id.homebase.chat.conversationlist

import id.homebase.api.file.FileOperationsProvider
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes

// The browser has no filesystem path for a picked PlatformFile (FileKit's PlatformFile.path is
// non-web-only); its bytes are reachable only via readBytes(). Copy them into the okio in-memory
// cache so the path-based send pipeline can read them back. Without this, web sends fail because
// file.toString() isn't an okio path ("file not found").
actual suspend fun PlatformFile.toUploadPath(fileOps: FileOperationsProvider): String =
    materializeBytesToUploadPath(fileOps, name, readBytes())

// No-op on web: the browser has no filesystem path to copy to and no security scope to lose. The
// picked PlatformFile keeps its bytes; toUploadPath() above materializes them at send time via
// readBytes(). (FileKit's copyTo / cacheDir aren't available on wasmJs.)
actual suspend fun PlatformFile.materializeForUpload(fileOps: FileOperationsProvider): PlatformFile = this

// Mint a blob: object URL straight from the picked browser File. This is O(1): the browser keeps
// the File's bytes and the <video>/canvas stream from the URL on demand — no readBytes(), no copy
// into wasm, no base64. Reused for poster, duration, filmstrip and playback (the old code base64'd
// the whole file separately for each of those, which is what made the editor slow on web).
actual fun PlatformFile.toPlayableUrl(): String = createObjectUrlFromFile(file)

actual fun revokePlayableUrl(url: String) {
    if (url.startsWith("blob:")) revokeObjectUrlJs(url)
}

private fun createObjectUrlFromFile(file: JsAny): String = js("URL.createObjectURL(file)")

private fun revokeObjectUrlJs(url: String): Unit = js("{ URL.revokeObjectURL(url); }")
