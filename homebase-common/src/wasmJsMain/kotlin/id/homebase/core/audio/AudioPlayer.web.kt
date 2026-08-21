@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, kotlin.io.encoding.ExperimentalEncodingApi::class)

package id.homebase.core.audio

import co.touchlab.kermit.Logger
import id.homebase.api.file.readWebFileBytes
import id.homebase.core.util.detectContentTypeFromExtensionOrHint
import kotlin.io.encoding.Base64

/*
 * `filePath` here is a path into the in-memory FakeFileSystem the decrypt-on-demand flow wrote to
 * (MediaDownloadHandler.handleDecryptFile), not a real file the browser can fetch. Read it back,
 * wrap the bytes in a Blob object URL and drive a detached HTMLAudioElement — the same bridge the
 * web video surface uses. Bytes cross to JS as Base64, the idiom used by HtmlVideoOverlay.web.kt.
 */

private class WebAudioPlayer : AudioPlayer {
    private var element: JsAny? = null
    private var objectUrl: String? = null
    private var observer: AudioPlaybackObserver? = null

    override fun play(filePath: String) {
        teardown()

        val bytes = readWebFileBytes(filePath)
        if (bytes == null) {
            Logger.e(tag = TAG) { "No decrypted audio at $filePath" }
            return
        }

        val url = audioBytesToObjectUrl(Base64.encode(bytes), audioMimeForPath(filePath))
        objectUrl = url

        val el = createAudioElement(url)
        addAudioProgressListener(el) { currentSec, durationSec ->
            observer?.onProgressUpdate(currentSec.toWholeSeconds(), durationSec.toWholeSeconds())
        }
        addAudioEndedListener(el) { observer?.onComplete() }
        addAudioErrorListener(el) { code -> Logger.e(tag = TAG) { "Audio element error $code" } }
        element = el

        playAudioElement(el) { reason -> Logger.w(tag = TAG) { "play() rejected: $reason" } }
    }

    override fun jump(seconds: Int) {
        val el = element ?: return
        setAudioCurrentTime(el, seconds.coerceAtLeast(0).toDouble())
    }

    override fun resume() {
        val el = element ?: return
        playAudioElement(el) { reason -> Logger.w(tag = TAG) { "resume() rejected: $reason" } }
    }

    override fun pause() {
        element?.let { pauseAudioElement(it) }
    }

    override fun stop() {
        val el = element ?: return
        pauseAudioElement(el)
        setAudioCurrentTime(el, 0.0)
    }

    override fun release() {
        teardown()
        observer = null
    }

    override fun setPlaybackObserver(observer: AudioPlaybackObserver) {
        this.observer = observer
    }

    private fun teardown() {
        element?.let { detachAudioElement(it) }
        element = null
        objectUrl?.let { revokeAudioObjectUrl(it) }
        objectUrl = null
    }

    private companion object {
        const val TAG = "WebAudioPlayer"
    }
}

// A stream muxed without a duration box reports NaN/Infinity; 0 tells the widget to keep the
// length it already read off the payload descriptor.
private fun Double.toWholeSeconds(): Int =
    if (isFinite() && this > 0.0) toInt() else 0

private fun audioMimeForPath(path: String): String =
    detectContentTypeFromExtensionOrHint(path).takeIf { it.startsWith("audio/") } ?: "audio/mp4"

private fun audioBytesToObjectUrl(base64: String, mimeType: String): String = js(
    """{
        var bin = atob(base64);
        var arr = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
        return URL.createObjectURL(new Blob([arr], { type: mimeType }));
    }"""
)

private fun revokeAudioObjectUrl(url: String): Unit = js("{ URL.revokeObjectURL(url); }")

private fun createAudioElement(url: String): JsAny = js(
    """{
        var a = new Audio();
        a.preload = 'auto';
        a.src = url;
        a.load();
        return a;
    }"""
)

private fun playAudioElement(el: JsAny, onRejected: (String) -> Unit): Unit = js(
    """{
        var p = el.play();
        if (p && typeof p.catch === 'function') p.catch(function (e) { onRejected(String(e)); });
    }"""
)

private fun pauseAudioElement(el: JsAny): Unit = js("{ try { el.pause(); } catch (e) {} }")

private fun setAudioCurrentTime(el: JsAny, seconds: Double): Unit = js(
    "{ try { el.currentTime = seconds; } catch (e) {} }"
)

private fun addAudioProgressListener(el: JsAny, cb: (Double, Double) -> Unit): Unit = js(
    """{
        var emit = function () { cb(el.currentTime || 0, el.duration || 0); };
        el.addEventListener('loadedmetadata', emit);
        el.addEventListener('playing', emit);
        el.addEventListener('timeupdate', emit);
        el.addEventListener('seeked', emit);
    }"""
)

private fun addAudioEndedListener(el: JsAny, cb: () -> Unit): Unit = js(
    "{ el.addEventListener('ended', function () { cb(); }); }"
)

private fun addAudioErrorListener(el: JsAny, cb: (Int) -> Unit): Unit = js(
    "{ el.addEventListener('error', function () { cb((el.error && el.error.code) || 0); }); }"
)

private fun detachAudioElement(el: JsAny): Unit = js(
    """{
        try { el.pause(); } catch (e) {}
        try { el.removeAttribute('src'); el.load(); } catch (e) {}
    }"""
)

actual fun getAudioPlayer(): AudioPlayer = WebAudioPlayer()
