package id.homebase.core.util

import id.homebase.api.util.toJsUint8Array
import kotlinx.coroutines.await
import kotlin.js.Promise

actual suspend fun openBrowserSave(suggestedName: String, mimeType: String): StreamedSave? {
    val saver = openSaver(suggestedName, mimeType).await<JsAny?>() ?: return null
    return object : StreamedSave {
        override suspend fun write(bytes: ByteArray) {
            saverWrite(saver, bytes.toJsUint8Array()).await<JsAny?>()
        }

        override suspend fun close() {
            saverClose(saver).await<JsAny?>()
        }

        override suspend fun abort() {
            saverAbort(saver).await<JsAny?>()
        }
    }
}

private fun saverWrite(saver: JsAny, bytes: JsAny): Promise<JsAny?> = js("saver.write(bytes)")
private fun saverClose(saver: JsAny): Promise<JsAny?> = js("saver.close()")
private fun saverAbort(saver: JsAny): Promise<JsAny?> = js("saver.abort()")

/*
 * Each saver awaits its writes, so a slow disk holds the download back instead of filling memory. The private storage
 * keeps one folder of finished files; each is downloaded from a blob URL that reads it from disk, and one is removed
 * once it's an hour old, by when its download has long read it.
 */
private fun openSaver(name: String, mime: String): Promise<JsAny?> = js(
    """(async function () {
        function download(blob) {
            var url = URL.createObjectURL(blob);
            var a = document.createElement('a');
            a.href = url;
            a.download = name;
            a.style.display = 'none';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            // Revoking in the same task cancels the download in Safari and Firefox.
            setTimeout(function () { URL.revokeObjectURL(url); }, 60000);
        }
        if (typeof window.showSaveFilePicker === 'function') {
            var picked;
            try {
                picked = await window.showSaveFilePicker({ suggestedName: name });
            } catch (e) {
                if (e && e.name === 'AbortError') return null;
                throw e;
            }
            var out = await picked.createWritable();
            return {
                write: function (b) { return out.write(b); },
                close: function () { return out.close(); },
                abort: function () { return out.abort(); },
            };
        }
        var dir = null;
        try {
            var root = await navigator.storage.getDirectory();
            dir = await root.getDirectoryHandle('hb-downloads', { create: true });
        } catch (e) {
            dir = null;
        }
        if (dir) {
            var now = Date.now();
            for await (var key of dir.keys()) {
                if (now - parseInt(key, 10) > 3600000) {
                    try { await dir.removeEntry(key); } catch (e) {}
                }
            }
            var fileName = now + '-' + Math.random().toString(36).slice(2);
            var handle = await dir.getFileHandle(fileName, { create: true });
            if (typeof handle.createWritable === 'function') {
                var stored = await handle.createWritable();
                return {
                    write: function (b) { return stored.write(b); },
                    close: async function () {
                        await stored.close();
                        download(await handle.getFile());
                    },
                    abort: async function () {
                        try { await stored.abort(); } catch (e) {}
                        try { await dir.removeEntry(fileName); } catch (e) {}
                    },
                };
            }
            try { await dir.removeEntry(fileName); } catch (e) {}
        }
        var parts = [];
        return {
            write: function (b) { parts.push(b); return Promise.resolve(); },
            close: function () { download(new Blob(parts, { type: mime })); parts = []; return Promise.resolve(); },
            abort: function () { parts = []; return Promise.resolve(); },
        };
    })()"""
)
