@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package id.homebase.core.clipboard

import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.await

actual fun getImageFromClipboard(): ByteArray? {
    // Browser clipboard image access requires async Clipboard API which
    // is not compatible with this synchronous expect/actual signature.
    return null
}

// navigator.clipboard.read() is gated by the browser's transient user-activation window
// (~5s after a user gesture like a tap), not by strict same-task/synchronous execution — the
// pasteScope.launch { } dispatch hop before this runs is fine as long as it lands inside that
// window. Call this promptly from the menu-tap handler; expect a first-use permission prompt.
actual suspend fun readClipboardImage(): ByteArray? {
    val b64 = readClipboardImageJs().await<JsString>().toString()
    return if (b64.isBlank()) null else Base64.decode(b64)
}

/**
 * Reads the first image item off the OS clipboard via the async Clipboard API, base64-encoding
 * its bytes in JS (same string-bridge idiom as FFmpegBridge / BrowserVideoDecoder — keeps the
 * boundary free of typed-array ownership concerns). Resolves to `""` on any failure: permission
 * denied, missing user gesture, unsupported browser, or no image item present.
 */
private fun readClipboardImageJs(): Promise<JsString> = js(
    """{
        return new Promise(function (resolve) {
            try {
                navigator.clipboard.read().then(function (items) {
                    var item = null, type = null;
                    for (var i = 0; i < items.length; i++) {
                        var t = items[i].types.find(function (t) { return t.indexOf('image/') === 0; });
                        if (t) { item = items[i]; type = t; break; }
                    }
                    if (!item) { resolve(''); return; }
                    item.getType(type).then(function (blob) {
                        blob.arrayBuffer().then(function (buf) {
                            var u8 = new Uint8Array(buf);
                            var s = '';
                            var chunk = 0x8000;
                            for (var j = 0; j < u8.length; j += chunk) {
                                s += String.fromCharCode.apply(null, u8.subarray(j, Math.min(j + chunk, u8.length)));
                            }
                            resolve(btoa(s));
                        }, function () { resolve(''); });
                    }, function () { resolve(''); });
                }, function () { resolve(''); });
            } catch (e) { resolve(''); }
        });
    }"""
)
