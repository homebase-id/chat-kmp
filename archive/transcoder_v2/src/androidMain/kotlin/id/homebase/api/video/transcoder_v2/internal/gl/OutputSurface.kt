package id.homebase.api.video.transcoder_v2.internal.gl

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import id.homebase.api.video.transcoder_v2.GlPipelineException

/**
 * Wraps a `SurfaceTexture` (bound to a `GL_TEXTURE_EXTERNAL_OES`) so a
 * `MediaCodec` decoder configured with surface output writes its decoded
 * frames into our GL pipeline. Each [awaitNewImage] latches the next
 * frame; [drawImage] paints it onto the currently-current GL framebuffer
 * (which the caller arranges to be the encoder's input surface via
 * [InputSurface.makeCurrent]).
 *
 * Ported from the no-arg constructor of Signal-Android's
 * `transcoder/videoconverter/OutputSurface.java` (line 87). NO EGL setup
 * here — we inherit the EGL context from [InputSurface].
 *
 * Threading: SurfaceTexture's `onFrameAvailable` dispatches on the
 * Looper of the creating thread, or the main Looper if that thread has
 * none. The transcode pump runs on a `Dispatchers.IO` thread (no
 * Looper), so the callback goes to the main Looper — that's the right
 * shape because our pump thread is blocked in `wait()` when waiting and
 * couldn't dispatch its own callback. **MUST be constructed on a
 * Looper-less thread.**
 */
internal class OutputSurface(
    viewportW: Int,
    viewportH: Int,
) : SurfaceTexture.OnFrameAvailableListener {

    private val textureRender = TextureRender(viewportW, viewportH)
    private val surfaceTexture: SurfaceTexture
    private var surface: Surface?

    private val frameSyncLock = Any()
    private var frameAvailable = false

    init {
        textureRender.surfaceCreated()
        surfaceTexture = SurfaceTexture(textureRender.textureId).apply {
            setOnFrameAvailableListener(this@OutputSurface)
        }
        surface = Surface(surfaceTexture)
    }

    fun getSurface(): Surface = surface ?: error("OutputSurface already released")

    /** Blocks up to ~750ms for the next [onFrameAvailable] then latches. */
    fun awaitNewImage() {
        val timeoutMs = 750L
        synchronized(frameSyncLock) {
            val expire = System.currentTimeMillis() + timeoutMs
            while (!frameAvailable) {
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (frameSyncLock as Object).wait(timeoutMs)
                if (!frameAvailable && System.currentTimeMillis() > expire) {
                    throw GlPipelineException("Surface frame wait timed out (>${timeoutMs}ms)")
                }
            }
            frameAvailable = false
        }
        TextureRender.checkGlError("before updateTexImage")
        surfaceTexture.updateTexImage()
    }

    /** Paints the current SurfaceTexture frame onto the current GL framebuffer. */
    fun drawImage() {
        textureRender.drawFrame(surfaceTexture)
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        synchronized(frameSyncLock) {
            if (frameAvailable) {
                Log.w(TAG, "frameAvailable already set — frame could be dropped")
            }
            frameAvailable = true
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (frameSyncLock as Object).notifyAll()
        }
    }

    fun release() {
        surface?.release()
        surface = null
        // SurfaceTexture is released implicitly when the underlying GL texture
        // is destroyed by the EGL context teardown in InputSurface.release().
        // Explicit surfaceTexture.release() here would race with that.
    }

    companion object {
        private const val TAG = "OutputSurface(gl)"
    }
}
