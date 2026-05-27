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
 */
internal class BrowserVideoDecoder : VideoDecoder {

    override suspend fun extractPosterFrame(videoPath: String): ByteArray? {
        val bytes = readBytes(videoPath) ?: return null
        val out = extractPosterFrameJs(Base64.encode(bytes), mimeFromPath(videoPath))
            .await<JsString>().toString()
        if (out.isBlank()) return null
        return Base64.decode(out)
    }

    override fun extractThumbnailStrip(
        videoPath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
    ): Flow<IndexedFrame> = channelFlow {
        if (frameCount <= 0 || durationMs <= 0L) return@channelFlow
        val bytes = readBytes(videoPath) ?: return@channelFlow

        val step = durationMs.toDouble() / frameCount
        val timesCsv = (0 until frameCount).joinToString(",") { i ->
            ((step * (i + 0.5)).toLong().coerceIn(0L, durationMs - 1)).toString()
        }

        // One JS call seeks to all N timestamps with a single <video>, returning an "index|jpeg"
        // record per line. We re-derive timeMs from the index here so the JS side doesn't need
        // to echo it back.
        val raw = extractStripFramesJs(Base64.encode(bytes), mimeFromPath(videoPath), timesCsv, targetHeightPx)
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
    }

    private fun readBytes(path: String): ByteArray? =
        runCatching { systemFileSystem.read(path.toPath()) { readByteArray() } }.getOrNull()
}

/**
 * Map a file extension to a Blob MIME so the `<video>` element picks the right demuxer. Most
 * browsers will sniff anyway, but Safari (iOS, macOS) is stricter — `video/mp4` on a `.mov`
 * file can be refused. Falls back to `'video/mp4'` for unknown extensions because that's the
 * dominant capture format in our upload pipeline.
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

private fun extractPosterFrameJs(videoBase64: String, mimeType: String): Promise<JsString> = js(
    """{
        return new Promise(function (resolve) {
            var done = false;
            var url = null;
            function finish(v) {
                if (done) return;
                done = true;
                if (url) URL.revokeObjectURL(url);
                resolve(v);
            }
            try {
                var bin = atob(videoBase64);
                var arr = new Uint8Array(bin.length);
                for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
                url = URL.createObjectURL(new Blob([arr], { type: mimeType }));

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
 * Seeks through [timesMsCsv] timestamps on a single hidden `<video>` element, drawing each into
 * a reusable `<canvas>` sized to [targetH]. Returns `idx|base64\n` lines — base64-empty entries
 * for timestamps that failed are dropped on the Kotlin side. Resolves with `""` if the browser
 * refuses the codec entirely (the file fails to load) so the tier-runner falls through.
 */
private fun extractStripFramesJs(
    videoBase64: String,
    mimeType: String,
    timesMsCsv: String,
    targetH: Int,
): Promise<JsString> = js(
    """{
        return new Promise(function (resolve) {
            var done = false;
            var url = null;
            function finish(v) {
                if (done) return;
                done = true;
                if (url) URL.revokeObjectURL(url);
                resolve(v);
            }
            try {
                var bin = atob(videoBase64);
                var arr = new Uint8Array(bin.length);
                for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
                url = URL.createObjectURL(new Blob([arr], { type: mimeType }));

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
                        idx++; next();
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
