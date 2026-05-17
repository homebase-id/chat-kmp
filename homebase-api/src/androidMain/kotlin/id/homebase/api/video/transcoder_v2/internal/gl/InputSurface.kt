package id.homebase.api.video.transcoder_v2.internal.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface
import id.homebase.api.video.transcoder_v2.GlPipelineException

/**
 * Wraps the `Surface` returned by `MediaCodec.createInputSurface()` in an
 * EGL14 window surface so a GL pipeline can render frames into the video
 * encoder. After [makeCurrent], the GL framebuffer = the encoder's input.
 * Each [swapBuffers] commits one frame to the encoder.
 *
 * Ported from Signal-Android's `transcoder/videoconverter/InputSurface.java`
 * (AOSP-licensed). See SPEC.md §10 amendment.
 *
 * Threading: the EGL context is bound to whichever OS thread calls
 * [makeCurrent]. The caller must construct AND drive this object from a
 * single thread (our transcoder uses `Dispatchers.IO` inside a sync
 * `coroutineScope` block — no suspension points means stable thread).
 */
internal class InputSurface(surface: Surface) {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surface: Surface? = surface

    init {
        eglSetup(surface)
    }

    fun getSurface(): Surface = surface ?: error("InputSurface already released")

    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw GlPipelineException("eglMakeCurrent failed")
        }
    }

    fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(eglDisplay, eglSurface)

    /** Time in nanoseconds (microseconds × 1000). */
    fun setPresentationTime(nsec: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsec)
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (EGL14.eglGetCurrentContext() == eglContext) {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
            }
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            // Deliberately NOT calling eglTerminate — Signal doesn't either; it
            // breaks subsequent EGL use in the same process.
        }
        surface?.release()

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        surface = null
    }

    private fun eglSetup(target: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw GlPipelineException("unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            eglDisplay = EGL14.EGL_NO_DISPLAY
            throw GlPipelineException("unable to initialize EGL14")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
            throw GlPipelineException("unable to find RGB888+recordable ES2 EGL config")
        }
        val config = configs[0] ?: throw GlPipelineException("null EGLConfig")

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        checkEglError("eglCreateContext")
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw GlPipelineException("null EGL context")
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, target, surfaceAttribs, 0)
        checkEglError("eglCreateWindowSurface")
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw GlPipelineException("EGL window surface was null")
        }
    }

    private fun checkEglError(msg: String) {
        var error = EGL14.eglGetError()
        var failed = false
        while (error != EGL14.EGL_SUCCESS) {
            Log.e(TAG, "$msg: EGL error: 0x${Integer.toHexString(error)}")
            failed = true
            error = EGL14.eglGetError()
        }
        if (failed) throw GlPipelineException("EGL error (see log)")
    }

    companion object {
        private const val TAG = "InputSurface"
        private const val EGL_OPENGL_ES2_BIT = 4
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
