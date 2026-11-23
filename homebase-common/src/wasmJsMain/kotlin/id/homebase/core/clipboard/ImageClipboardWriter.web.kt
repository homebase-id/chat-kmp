@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, DelicateCoroutinesApi::class)

package id.homebase.core.clipboard

import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

actual val canCopyImageToClipboard: Boolean = clipboardWriteSupported()

// Safari and Firefox only accept the write inside the click, and the image is fetched and decrypted
// first. So the write starts at once with a ClipboardItem holding the unresolved promise.
internal actual suspend fun writePngToClipboard(png: suspend () -> ByteArray) {
    val base64 = GlobalScope.promise { Base64.encode(png()).toJsString() }
    writePngJs(base64).await<JsAny?>()
}

private fun clipboardWriteSupported(): Boolean = js(
    "typeof ClipboardItem !== 'undefined' && !!navigator.clipboard && typeof navigator.clipboard.write === 'function'"
)

@Suppress("UNUSED_PARAMETER")
private fun writePngJs(base64: Promise<JsString>): Promise<JsAny?> = js(
    """{
        var blob = base64.then(function (b64) {
            var raw = atob(b64);
            var u8 = new Uint8Array(raw.length);
            for (var i = 0; i < raw.length; i++) u8[i] = raw.charCodeAt(i);
            return new Blob([u8], { type: 'image/png' });
        });
        return navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]);
    }"""
)
