@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package id.homebase.api.video

import id.homebase.api.file.systemFileSystem
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import okio.Path.Companion.toPath

/**
 * Browser-native poster-frame extraction — decodes one frame with an HTML5 `<video>`
 * element + `<canvas>`, so the ~22 MB ffmpeg core never loads just to make a thumbnail
 * (mirrors how the native actuals delegate to MediaMetadataRetriever instead of ffmpeg).
 */
actual object VideoThumbnailExtractor {

    actual suspend fun extractPosterFrame(videoPath: String): ByteArray? {
        val bytes = runCatching {
            systemFileSystem.read(videoPath.toPath()) { readByteArray() }
        }.getOrNull() ?: return null
        val out = extractPosterFrameJs(Base64.encode(bytes)).await<JsString>().toString()
        if (out.isBlank()) return null
        return Base64.decode(out)
    }

    // Trim-scrubber filmstrip. Browser frame-stepping is out of scope for the first wasm pass;
    // the trim UI degrades to no preview frames rather than blocking the build.
    actual fun extractThumbnailStrip(
        filePath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
    ): Flow<IndexedFrame> = emptyFlow()
}

private fun extractPosterFrameJs(videoBase64: String): Promise<JsString> = js(
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
                url = URL.createObjectURL(new Blob([arr], { type: 'video/mp4' }));

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
                        }, 'image/jpeg', 0.8);
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
