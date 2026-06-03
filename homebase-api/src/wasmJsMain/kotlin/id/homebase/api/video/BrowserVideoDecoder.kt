@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package id.homebase.api.video

import id.homebase.api.file.systemFileSystem
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import okio.Path.Companion.toPath

/**
 * Browser-native primary decoder. Uses an HTML5 `<video>` element + `<canvas>` so the ~22 MB
 * ffmpeg.wasm core never loads just to make thumbnails (parity with how the native actuals
 * delegate to MediaMetadataRetriever / AVAssetImageGenerator instead of ffmpeg). When the browser
 * can't decode the codec (some HEVC variants on Firefox, etc.), [TieredVideoDecoder] falls
 * through to [FFmpegWasmVideoDecoder].
 *
 * `videoPath` may be either:
 *  - a `blob:` object URL (the editor's fast path — minted once from the picked File and reused for
 *    poster, duration, filmstrip and playback). We feed it straight to `<video>.src` — no bytes are
 *    read into wasm and nothing is base64'd. We do NOT revoke it; its owner (the attachment editor)
 *    does.
 *  - an okio path (e.g. an ffmpeg-produced file, or `FFmpegUtils.grabThumbnail`). We read the bytes
 *    and mint a short-lived blob URL for the `<video>`, then revoke it. This is the only path that
 *    still base64s, and it's off the interactive hot path.
 */
internal class BrowserVideoDecoder : VideoDecoder {

    override suspend fun extractPosterFrame(videoPath: String): ByteArray? {
        val handle = VideoUrlHandle.resolve(videoPath) ?: return null
        return try {
            val out = extractPosterFrameJs(handle.url).await<JsString>().toString()
            if (out.isBlank()) null else Base64.decode(out)
        } finally {
            handle.release()
        }
    }

    // Primary decoder — TieredVideoDecoder only populates `skipMask` for fallbacks. Even if
    // we wanted to honor it, our JS shim seeks through all timestamps in one Promise; per-
    // index skipping isn't a meaningful optimization.
    override fun extractThumbnailStrip(
        videoPath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
        skipMask: BooleanArray?,
    ): Flow<IndexedFrame> = channelFlow {
        if (frameCount <= 0 || durationMs <= 0L) return@channelFlow
        val handle = VideoUrlHandle.resolve(videoPath) ?: return@channelFlow
        try {
            val step = durationMs.toDouble() / frameCount
            val timesCsv = (0 until frameCount).joinToString(",") { i ->
                ((step * (i + 0.5)).toLong().coerceIn(0L, durationMs - 1)).toString()
            }

            // One JS call seeks to all N timestamps with a single <video>, returning an "index|jpeg"
            // record per line. We re-derive timeMs from the index here so the JS side doesn't need
            // to echo it back.
            val raw = extractStripFramesJs(handle.url, timesCsv, targetHeightPx)
                .await<JsString>().toString()
            if (raw.isBlank()) return@channelFlow

            for (line in raw.split('\n')) {
                if (line.isBlank()) continue
                val sep = line.indexOf('|')
                if (sep <= 0) continue
                val idx = line.substring(0, sep).toIntOrNull() ?: continue
                if (idx !in 0 until frameCount) continue
                val b64 = line.substring(sep + 1)
                if (b64.isBlank()) continue
                val jpeg = runCatching { Base64.decode(b64) }.getOrNull() ?: continue
                if (jpeg.size < 16) continue
                val timeMs = (step * (idx + 0.5)).toLong()
                trySend(IndexedFrame(idx, timeMs, jpeg))
            }
        } finally {
            handle.release()
        }
    }
}

/**
 * Resolves a decoder `videoPath` to a blob URL the `<video>` element can play, tracking whether we
 * created it (and must therefore revoke it). A `blob:` input is used as-is and not owned; an okio
 * path is read + wrapped in a fresh, owned blob URL.
 */
private class VideoUrlHandle private constructor(val url: String, private val owned: Boolean) {
    fun release() {
        if (owned) revokeObjectUrlJs(url)
    }

    companion object {
        fun resolve(videoPath: String): VideoUrlHandle? {
            if (videoPath.startsWith("blob:")) return VideoUrlHandle(videoPath, owned = false)
            val bytes = readOkioBytes(videoPath) ?: return null
            val url = objectUrlFromBytesJs(Base64.encode(bytes), mimeFromPath(videoPath))
            return VideoUrlHandle(url, owned = true)
        }
    }
}

private fun readOkioBytes(path: String): ByteArray? =
    runCatching { systemFileSystem.read(path.toPath()) { readByteArray() } }.getOrNull()

/**
 * Map a file extension to a Blob MIME so the `<video>` element picks the right demuxer. Most
 * browsers will sniff anyway, but Safari (iOS, macOS) is stricter — `video/mp4` on a `.mov`
 * file can be refused. Falls back to `'video/mp4'` for unknown extensions because that's the
 * dominant capture format in our upload pipeline. (Only used for the okio fallback; a blob URL
 * minted from a picked File already carries the File's own MIME type.)
 */
private fun mimeFromPath(path: String): String =
    when (path.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "3gp", "3gpp" -> "video/3gpp"
        else -> "video/mp4"
    }

/** atob -> Uint8Array -> Blob -> object URL. Only for the okio fallback (bytes already in wasm). */
private fun objectUrlFromBytesJs(base64: String, mimeType: String): String = js(
    """{
        var bin = atob(base64);
        var arr = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
        return URL.createObjectURL(new Blob([arr], { type: mimeType }));
    }"""
)

private fun revokeObjectUrlJs(url: String): Unit = js("{ URL.revokeObjectURL(url); }")

/**
 * Loads [url] into a hidden `<video>`, seeks slightly past 0, and returns the first frame as
 * base64 JPEG (or `""` on any failure). Does NOT revoke [url] — the caller owns its lifetime.
 */
private fun extractPosterFrameJs(url: String): Promise<JsString> = js(
    """{
        return new Promise(function (resolve) {
            var done = false;
            function finish(v) {
                if (done) return;
                done = true;
                resolve(v);
            }
            try {
                var video = document.createElement('video');
                video.muted = true;
                video.playsInline = true;
                video.preload = 'auto';

                function draw() {
                    if (done) return;
                    try {
                        var w = video.videoWidth, h = video.videoHeight;
                        if (!w || !h) { finish(''); return; }
                        var canvas = document.createElement('canvas');
                        canvas.width = w;
                        canvas.height = h;
                        canvas.getContext('2d').drawImage(video, 0, 0, w, h);
                        canvas.toBlob(function (blob) {
                            if (!blob) { finish(''); return; }
                            blob.arrayBuffer().then(function (buf) {
                                var u8 = new Uint8Array(buf);
                                var s = '';
                                var chunk = 0x8000;
                                for (var j = 0; j < u8.length; j += chunk) {
                                    s += String.fromCharCode.apply(null, u8.subarray(j, Math.min(j + chunk, u8.length)));
                                }
                                finish(btoa(s));
                            }, function () { finish(''); });
                        // Quality literal must mirror VideoThumbnailQuality.POSTER_JPEG_QUALITY_0_TO_1.
                        }, 'image/jpeg', 0.75);
                    } catch (e) { finish(''); }
                }

                video.onloadeddata = function () {
                    // Seek slightly past 0 to dodge an all-black first frame; draw on seeked.
                    try {
                        var t = Math.min(0.1, (video.duration || 0) / 2);
                        if (t > 0) { video.currentTime = t; } else { draw(); }
                    } catch (e) { draw(); }
                };
                video.onseeked = draw;
                video.onerror = function () { finish(''); };
                video.src = url;
                // Safety net: never hang the coroutine if the element stalls.
                setTimeout(function () { finish(''); }, 15000);
            } catch (e) { finish(''); }
        });
    }"""
)

/**
 * Seeks through [timesMsCsv] timestamps on a single hidden `<video>` element loaded from [url],
 * drawing each into a reusable `<canvas>` sized to [targetH]. Returns `idx|base64\n` lines —
 * base64-empty entries for timestamps that failed are dropped on the Kotlin side. Resolves with
 * `""` if the browser refuses the codec entirely. Does NOT revoke [url] — the caller owns it.
 */
private fun extractStripFramesJs(
    url: String,
    timesMsCsv: String,
    targetH: Int,
): Promise<JsString> = js(
    """{
        return new Promise(function (resolve) {
            var done = false;
            function finish(v) {
                if (done) return;
                done = true;
                resolve(v);
            }
            try {
                var times = timesMsCsv.split(',').map(function (s) { return parseInt(s, 10); });
                var video = document.createElement('video');
                video.muted = true;
                video.playsInline = true;
                video.preload = 'auto';

                var canvas = document.createElement('canvas');
                var ctx = canvas.getContext('2d');
                var lines = [];
                var idx = 0;

                function drawCurrent() {
                    var w = video.videoWidth, h = video.videoHeight;
                    if (!w || !h) return '';
                    var outH = targetH;
                    var outW = Math.max(1, Math.round(w * (outH / h)));
                    if (canvas.width !== outW || canvas.height !== outH) {
                        canvas.width = outW;
                        canvas.height = outH;
                    }
                    ctx.drawImage(video, 0, 0, outW, outH);
                    try {
                        // Quality literal must mirror VideoThumbnailQuality.STRIP_JPEG_QUALITY_0_TO_1.
                        return canvas.toDataURL('image/jpeg', 0.6).split(',')[1] || '';
                    } catch (e) {
                        return '';
                    }
                }

                function next() {
                    if (idx >= times.length) { finish(lines.join('\n')); return; }
                    var tMs = times[idx];
                    var tSec = Math.max(0, Math.min((video.duration || 0) - 0.001, tMs / 1000));
                    try {
                        video.currentTime = tSec;
                    } catch (e) {
                        // Defer the retry through the task queue so a codec that throws on
                        // every seek can't blow the JS stack via deep recursion. Cheap on the
                        // happy path because `currentTime` doesn't throw, so we never reach
                        // this branch in normal operation.
                        idx++;
                        setTimeout(next, 0);
                    }
                }

                video.onseeked = function () {
                    var b64 = drawCurrent();
                    if (b64) lines.push(idx + '|' + b64);
                    idx++;
                    next();
                };
                video.onloadedmetadata = function () { next(); };
                video.onerror = function () { finish(''); };
                video.src = url;

                setTimeout(function () { finish(lines.join('\n')); }, 30000);
            } catch (e) { finish(''); }
        });
    }"""
)
