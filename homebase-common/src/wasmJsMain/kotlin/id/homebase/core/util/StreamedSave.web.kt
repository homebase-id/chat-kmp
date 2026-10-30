package id.homebase.core.util

import id.homebase.api.util.toJsUint8Array
import kotlinx.coroutines.await
import kotlin.js.Promise

actual suspend fun openBrowserSave(suggestedName: String, mimeType: String): StreamedSave? {
    val saver = openSaver(suggestedName, mimeType).await<JsAny?>() ?: return null
    return object : StreamedSave {
        // Each crossing into JS copies and awaits, so small writes (a ZIP's headers) are gathered first.
        private val pending = ArrayList<ByteArray>()
        private var pendingSize = 0

        override suspend fun write(bytes: ByteArray) {
            pending += bytes
            pendingSize += bytes.size
            if (pendingSize >= FLUSH_AT) flush()
        }

        override suspend fun close() {
            flush()
            // A saver that wrote to a picked file is done; the others hand back what to download.
            saverClose(saver).await<JsAny?>()?.let { triggerBrowserDownload(objectUrl(it), suggestedName) }
        }

        override suspend fun abort() {
            pending.clear()
            saverAbort(saver).await<JsAny?>()
        }

        private suspend fun flush() {
            if (pending.isEmpty()) return
            val joined = if (pending.size == 1) pending[0] else ByteArray(pendingSize).also { all ->
                var at = 0
                for (part in pending) {
                    part.copyInto(all, at)
                    at += part.size
                }
            }
            pending.clear()
            pendingSize = 0
            saverWrite(saver, joined.toJsUint8Array()).await<JsAny?>()
        }
    }
}

private const val FLUSH_AT = 1 shl 20

private fun objectUrl(blob: JsAny): String = js("URL.createObjectURL(blob)")
private fun saverWrite(saver: JsAny, bytes: JsAny): Promise<JsAny?> = js("saver.write(bytes)")
private fun saverClose(saver: JsAny): Promise<JsAny?> = js("saver.close()")
private fun saverAbort(saver: JsAny): Promise<JsAny?> = js("saver.abort()")

/*
 * Each saver awaits its writes, so a slow disk holds the download back instead of filling memory. The private storage
 * keeps one folder of finished files, each downloaded from a blob that reads it from disk; one is removed once it's
 * an hour old, by when its download has long read it. Close resolves with that blob, or null when the picked file
 * already holds everything.
 */
private fun openSaver(name: String, mime: String): Promise<JsAny?> = js(
    """(async function () {
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
                close: async function () { await out.close(); return null; },
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
                    close: async function () { await stored.close(); return await handle.getFile(); },
                    abort: async function () {
                        try { await stored.abort(); } catch (e) {}
                        try { await dir.removeEntry(fileName); } catch (e) {}
                    },
                };
            }
            try { await dir.removeEntry(fileName); } catch (e) {}
        }
        // Folded into a Blob every 64 MiB, which a browser may keep on disk rather than in the page's memory.
        var parts = [];
        var size = 0;
        return {
            write: function (b) {
                parts.push(b);
                size += b.length;
                if (size >= 67108864) { parts = [new Blob(parts)]; size = 0; }
                return Promise.resolve();
            },
            close: function () { var blob = new Blob(parts, { type: mime }); parts = []; return Promise.resolve(blob); },
            abort: function () { parts = []; return Promise.resolve(); },
        };
    })()"""
)
