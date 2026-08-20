@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    kotlin.js.ExperimentalWasmJsInterop::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class,
)

package id.homebase.core.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.window.LocalActiveClipEventsTarget
import co.touchlab.kermit.Logger
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener as EventListenerInterface

/**
 * Listens on the same element Compose gives its own text fields, which is the only element the
 * browser dispatches clipboard events to.
 *
 * Compose renders into a canvas inside a shadow root, and browsers will not reliably dispatch
 * clipboard events to a canvas. So on Cmd/Ctrl+V it focuses a real input — the text field's
 * backing input when one is focused, otherwise a hidden textarea — and lets the browser deliver
 * the event there. [LocalActiveClipEventsTarget] hands out whichever it currently is; Compose's
 * own `rememberClipboardEventsHandler`, which is what makes text paste work today, does exactly
 * this. A document-level listener is not equivalent: it depends on the event escaping the shadow
 * root, and it cannot see the backing input at all.
 *
 * [enabled] must track the field's focus for the same reason Compose gates on `state.hasFocus` —
 * the target is resolved when the listener is attached, and it only resolves to the live backing
 * input while the field holds focus.
 *
 * The event is deliberately not consumed. Compose's own paste listener runs alongside this one
 * and calls preventDefault for the text it handles; a plain-text paste yields no image here and
 * falls through untouched.
 */
@Composable
actual fun ClipboardImagePasteEffect(enabled: Boolean, onImagePasted: (ByteArray) -> Unit) {
    val clipEventsTarget = LocalActiveClipEventsTarget.current
    val scope = rememberCoroutineScope()
    val currentOnImagePasted by rememberUpdatedState(onImagePasted)

    DisposableEffect(enabled, clipEventsTarget) {
        val target = if (enabled) clipEventsTarget() else null
        if (target == null) return@DisposableEffect onDispose { }

        val listener = EventListener { event ->
            // clipboardData is only live for the duration of the dispatch, so pull the image out
            // synchronously; only the byte read is deferred to the coroutine.
            val pending = pastedImageBase64(event)
            scope.launch {
                val base64 = runCatching { pending.await<JsString>().toString() }
                    .onFailure { Logger.w("Reading a pasted image failed", it) }
                    .getOrNull()
                    .orEmpty()
                if (base64.isNotBlank()) currentOnImagePasted(Base64.decode(base64))
            }
        }

        target.addEventListener(PASTE_EVENT, listener)
        onDispose { target.removeEventListener(PASTE_EVENT, listener) }
    }
}

private const val PASTE_EVENT = "paste"

// EventListener is a bare interface on wasm, so wrap the lambda in JS — the same shim
// Compose's own ClipboardEventsHandler uses.
@Suppress("UNUSED_PARAMETER")
private fun EventListener(handler: (Event) -> Unit): EventListenerInterface =
    js("(event) => { handler(event) }")

/**
 * Resolves to the first image on the paste event as base64, or `""` when the paste carried none.
 *
 * Reads `items` first, where a screenshot arrives, and falls back to `files`, where a file
 * manager copy arrives. Base64 keeps the boundary free of typed-array ownership concerns — the
 * idiom [readClipboardImage] and the ffmpeg/video bridges already use.
 */
@Suppress("UNUSED_PARAMETER")
private fun pastedImageBase64(event: Event): Promise<JsString> = js(
    """{
        return new Promise(function (resolve) {
            try {
                var data = event.clipboardData;
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
