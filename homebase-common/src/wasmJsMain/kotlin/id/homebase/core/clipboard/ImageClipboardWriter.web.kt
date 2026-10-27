@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package id.homebase.core.clipboard

import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.await

// Browsers only reliably accept image/png in a ClipboardItem; base64 is the same string bridge readClipboard uses.
internal actual suspend fun writePngToClipboard(png: ByteArray) {
    writePngJs(Base64.encode(png)).await<JsAny?>()
}

private fun writePngJs(base64: String): Promise<JsAny?> = js(
    """{
        var raw = atob(base64);
        var u8 = new Uint8Array(raw.length);
        for (var i = 0; i < raw.length; i++) u8[i] = raw.charCodeAt(i);
        var blob = new Blob([u8], { type: 'image/png' });
        return navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]);
    }"""
)
