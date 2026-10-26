@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

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
// scope.launch { } dispatch hop before this runs is fine as long as it lands inside that
// window. Call this promptly from the menu-tap handler; expect a first-use permission prompt.
actual suspend fun readClipboard(): ClipboardContents {
    val result = readClipboardJs().await<ClipboardReadResult>()
    val image = result.image.takeIf { it.isNotBlank() }?.let { Base64.decode(it) }
    return ClipboardContents(image = image, text = result.text.ifEmpty { null })
}

actual fun clipboardMayHoldImage(): Boolean = true

private external interface ClipboardReadResult : JsAny {
    val image: String
    val text: String
}

/**
 * Reads the first image item (base64, the same string-bridge idiom as FFmpegBridge) and the first
 * text/plain item off the OS clipboard in one async Clipboard API read. Empty strings on any
 * failure: permission denied, missing user gesture, unsupported browser, or nothing of that kind.
 */
private fun readClipboardJs(): Promise<ClipboardReadResult> = js(
    """{
        return new Promise(function (resolve) {
            var result = { image: '', text: '' };
            function done() { resolve(result); }
            try {
                navigator.clipboard.read().then(function (items) {
                    var seenImage = false, seenText = false;
                    var pending = [];
                    for (var i = 0; i < items.length; i++) {
                        var types = items[i].types;
                        var imageType = types.find(function (t) { return t.indexOf('image/') === 0; });
                        if (imageType && !seenImage) {
                            seenImage = true;
                            pending.push(items[i].getType(imageType).then(function (blob) {
                                return blob.arrayBuffer().then(function (buf) {
                                    var u8 = new Uint8Array(buf);
                                    var s = '';
                                    var chunk = 0x8000;
                                    for (var j = 0; j < u8.length; j += chunk) {
                                        s += String.fromCharCode.apply(null, u8.subarray(j, Math.min(j + chunk, u8.length)));
                                    }
                                    result.image = btoa(s);
                                });
                            }).catch(function () {}));
                        }
                        if (types.indexOf('text/plain') >= 0 && !seenText) {
                            seenText = true;
                            pending.push(items[i].getType('text/plain').then(function (blob) {
                                return blob.text().then(function (t) { result.text = t || ''; });
                            }).catch(function () {}));
                        }
                    }
                    Promise.all(pending).then(done, done);
                }, done);
            } catch (e) { done(); }
        });
    }"""
)
