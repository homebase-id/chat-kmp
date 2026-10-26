@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package id.homebase.core.clipboard

import kotlin.js.Promise
import kotlinx.coroutines.await

actual suspend fun readClipboardText(): String? =
    readClipboardTextJs().await<JsString>().toString().ifEmpty { null }

private fun readClipboardTextJs(): Promise<JsString> = js(
    """{
        return new Promise(function (resolve) {
            try {
                navigator.clipboard.readText().then(
                    function (t) { resolve(t || ''); },
                    function () { resolve(''); });
            } catch (e) { resolve(''); }
        });
    }"""
)
