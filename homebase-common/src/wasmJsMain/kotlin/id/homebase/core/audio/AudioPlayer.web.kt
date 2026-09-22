@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package id.homebase.core.audio

import co.touchlab.kermit.Logger
import id.homebase.api.browser.guardJsCallback
import id.homebase.api.file.readWebFileBytes
import id.homebase.api.util.toBlobObjectUrl
import id.homebase.core.util.detectContentTypeFromExtensionOrHint

/*
 * `filePath` here is a path into the in-memory FakeFileSystem the decrypt-on-demand flow wrote to
 * (MediaDownloadHandler.handleDecryptFile), not a real file the browser can fetch. Read it back,
 * wrap the bytes in a Blob object URL and drive a detached HTMLAudioElement — the same bridge the
 * web video surface uses.
 */

private class WebAudioPlayer : AudioPlayer {
    private var element: JsAny? = null
    private var objectUrl: String? = null
    private var observer: AudioPlaybackObserver? = null
    private var speed = 1f

    override fun play(filePath: String) {
        teardown()

        val bytes = readWebFileBytes(filePath)
        if (bytes == null) {
            Logger.e(tag = TAG) { "No decrypted audio at $filePath" }
            return
        }

        val url = bytes.toBlobObjectUrl(audioMimeForPath(filePath))
        objectUrl = url

        val el = createAudioElement(url)
        setAudioPlaybackRate(el, speed.toDouble())
        addAudioProgressListener(el) { currentSec, durationSec ->
            guardJsCallback("audio.progress") {
                observer?.onProgressUpdate(currentSec.toMillis(), durationSec.toMillis())
            }
        }
        addAudioEndedListener(el) { guardJsCallback("audio.ended") { observer?.onComplete() } }
        addAudioErrorListener(el) { code ->
            guardJsCallback("audio.error") { Logger.e(tag = TAG) { "Audio element error $code" } }
        }
        element = el

        playAudioElement(el) { reason ->
            guardJsCallback("audio.play") { Logger.w(tag = TAG) { "play() rejected: $reason" } }
        }
    }

    override fun jumpTo(positionMs: Long) {
        val el = element ?: return
        setAudioCurrentTime(el, positionMs.coerceAtLeast(0) / 1000.0)
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed.coerceToPlaybackSpeed()
        element?.let { setAudioPlaybackRate(it, this.speed.toDouble()) }
    }

    override fun resume() {
        val el = element ?: return
        playAudioElement(el) { reason ->
            guardJsCallback("audio.resume") { Logger.w(tag = TAG) { "resume() rejected: $reason" } }
        }
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
private fun Double.toMillis(): Long =
    if (isFinite() && this > 0.0) (this * 1000).toLong() else 0

private fun audioMimeForPath(path: String): String =
    detectContentTypeFromExtensionOrHint(path).takeIf { it.startsWith("audio/") } ?: "audio/mp4"

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

private fun setAudioPlaybackRate(el: JsAny, rate: Double): Unit = js(
    "{ try { el.playbackRate = rate; } catch (e) {} }"
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
