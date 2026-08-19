@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package id.homebase.core.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import co.touchlab.kermit.Logger
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.events.Event

@Composable
actual fun ClipboardImagePasteEffect(enabled: Boolean, onImagePasted: (ByteArray) -> Unit) {
    val scope = rememberCoroutineScope()
    val currentOnImagePasted by rememberUpdatedState(onImagePasted)

    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }

        val listener: (Event) -> Unit = { event ->
            // clipboardData is only live for the duration of the dispatch, so pull the image
            // out synchronously here; only the byte read is deferred to the coroutine.
            val pending = pastedImageBase64(event)
            scope.launch {
                val base64 = runCatching { pending.await<JsString>().toString() }
                    .onFailure { Logger.w("Paste event image read failed", it) }
                    .getOrNull()
                    .orEmpty()
                if (base64.isNotBlank()) {
                    currentOnImagePasted(Base64.decode(base64))
                }
            }
        }

        // The listener sits on the document rather than a node: Compose renders into a canvas,
        // so there is no per-field DOM element for a paste to land on.
        document.addEventListener(PASTE_EVENT, listener)
        onDispose { document.removeEventListener(PASTE_EVENT, listener) }
    }
}

private const val PASTE_EVENT = "paste"

/**
 * Resolves to the first image on the paste event as base64, or `""` when the paste carried no
 * image — a plain-text paste lands here too and must fall through untouched.
 *
 * Reads `items` first (where a screenshot arrives) and falls back to `files` (where a file
 * manager copy arrives). Base64 keeps the boundary free of typed-array ownership concerns, the
 * same idiom [readClipboardImage] and the ffmpeg/video bridges use.
 */
@Suppress("UNUSED_PARAMETER")
private fun pastedImageBase64(event: Event): Promise<JsString> = js(
    """{
        return new Promise(function (resolve) {
            try {
                var data = event.clipboardData || window.clipboardData;
                if (!data) { resolve(''); return; }
                var file = null;
                var items = data.items || [];
                for (var i = 0; i < items.length; i++) {
                    if (items[i].kind === 'file' && items[i].type.indexOf('image/') === 0) {
                        file = items[i].getAsFile();
                        break;
                    }
                }
                if (!file && data.files) {
                    for (var j = 0; j < data.files.length; j++) {
                        if (data.files[j].type.indexOf('image/') === 0) {
                            file = data.files[j];
                            break;
                        }
                    }
                }
                if (!file) { resolve(''); return; }
                file.arrayBuffer().then(function (buf) {
                    var u8 = new Uint8Array(buf);
                    var s = '';
                    var chunk = 0x8000;
                    for (var k = 0; k < u8.length; k += chunk) {
                        s += String.fromCharCode.apply(null, u8.subarray(k, Math.min(k + chunk, u8.length)));
                    }
                    resolve(btoa(s));
                }, function () { resolve(''); });
            } catch (e) { resolve(''); }
        });
    }"""
)
