@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package id.homebase.chat.conversationlist

import id.homebase.api.util.isBlobUrl
import io.github.vinceglb.filekit.PlatformFile

// Mint a blob: object URL straight from the picked browser File. This is O(1): the browser keeps
// the File's bytes and the <video>/canvas stream from the URL on demand — no readBytes(), no copy
// into wasm, no base64. Reused for poster, duration, filmstrip and playback (the old code base64'd
// the whole file separately for each of those, which is what made the editor slow on web).
actual fun PlatformFile.toPlayableUrl(): String = createObjectUrlFromFile(file)

actual fun revokePlayableUrl(url: String) {
    if (url.isBlobUrl()) revokeObjectUrlJs(url)
}

private fun createObjectUrlFromFile(file: JsAny): String = js("URL.createObjectURL(file)")

private fun revokeObjectUrlJs(url: String): Unit = js("{ URL.revokeObjectURL(url); }")
