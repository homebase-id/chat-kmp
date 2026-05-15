package id.homebase.api.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * Holds state associated with a Surface used for MediaCodec decoder output.
 *
 * The (width,height) constructor prepares an EGL pbuffer surface, creates a SurfaceTexture
 * bound to a GL_TEXTURE_EXTERNAL_OES texture, and exposes [getSurface] for
 * MediaCodec.configure(). When a frame arrives, [awaitNewImage] + [drawImage] latch the
 * SurfaceTexture, render it onto the pbuffer, after which the caller can read pixels with
 * `GLES20.glReadPixels` into an ARGB_8888 bitmap.
 *
 * Ported from Signal-Android's lib/video/...videoconverter/{OutputSurface, TextureRender}.java
 * (AOSP-licensed). TextureRender is folded into a private class here because it is purely an
 * implementation detail of OutputSurface.
 *
 * Threading: SurfaceTexture's "frame available" message is dispatched to whatever Looper is
 * associated with the thread that owns the SurfaceTexture, or — if that thread has no Looper —
 * the main application Looper. To get the OnFrameAvailableListener wakeup, this class must
 * be constructed on a Looper-less thread (e.g. a Dispatchers.IO coroutine).
 */
internal class OutputSurface(
    width: Int,
    height: Int,
    flipX: Boolean,
) : SurfaceTexture.OnFrameAvailableListener {

    private var egl: EGL10? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    private val frameSyncLock: Any = Any()
    private var frameAvailable = false

    private var textureRender: TextureRender? = null

    init {
        require(width > 0 && height > 0) { "OutputSurface size must be > 0, got ${width}x$height" }
        eglSetup(width, height)
        makeCurrent()
        setup(flipX)
    }

    fun getSurface(): Surface = surface ?: error("OutputSurface already released")

    fun release() {
        val egl = this.egl
        if (egl != null) {
            if (egl.eglGetCurrentContext() == eglContext) {
                egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            }
            egl.eglDestroySurface(eglDisplay, eglSurface)
            egl.eglDestroyContext(eglDisplay, eglContext)
        }
        surface?.release()

        eglDisplay = null
        eglContext = null
        eglSurface = null
        this.egl = null
        textureRender = null
        surface = null
        surfaceTexture = null
    }

    /** Latches the next buffer into the texture. Blocks up to ~750ms waiting for [onFrameAvailable]. */
    fun awaitNewImage() {
        val timeoutMs = 750L
        synchronized(frameSyncLock) {
            val expire = System.currentTimeMillis() + timeoutMs
            while (!frameAvailable) {
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (frameSyncLock as Object).wait(timeoutMs)
                if (!frameAvailable && System.currentTimeMillis() > expire) {
                    throw RuntimeException("Surface frame wait timed out")
                }
            }
            frameAvailable = false
        }
        TextureRender.checkGlError("before updateTexImage")
        surfaceTexture!!.updateTexImage()
    }

    /** Renders the current SurfaceTexture frame onto the current EGL surface. */
    fun drawImage() {
        textureRender!!.drawFrame(surfaceTexture!!)
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        synchronized(frameSyncLock) {
            if (frameAvailable) {
                // Signal logs this as "frame could be dropped" — same pattern here.
                Log.w(TAG, "mFrameAvailable already set, frame could be dropped")
            }
            frameAvailable = true
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (frameSyncLock as Object).notifyAll()
        }
    }

    private fun setup(flipX: Boolean) {
        val tr = TextureRender(flipX)
        tr.surfaceCreated()
        textureRender = tr

        val st = SurfaceTexture(tr.textureId)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        surface = Surface(st)
    }

    private fun eglSetup(width: Int, height: Int) {
        val egl = EGLContext.getEGL() as EGL10
        this.egl = egl
        val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        if (!egl.eglInitialize(display, null)) {
            throw RuntimeException("unable to initialize EGL10")
        }
        eglDisplay = display

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val attribList = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL10.EGL_NONE,
        )
        if (!egl.eglChooseConfig(display, attribList, configs, 1, numConfigs)) {
            throw RuntimeException("unable to find RGB888+pbuffer EGL config")
        }
        val config = configs[0] ?: throw RuntimeException("no EGL config")

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE)
        eglContext = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, ctxAttribs)
        checkEglError("eglCreateContext")
        if (eglContext == null) {
            throw RuntimeException("null EGL context")
        }

        val surfaceAttribs = intArrayOf(
            EGL10.EGL_WIDTH, width,
            EGL10.EGL_HEIGHT, height,
            EGL10.EGL_NONE,
        )
        eglSurface = egl.eglCreatePbufferSurface(display, config, surfaceAttribs)
        checkEglError("eglCreatePbufferSurface")
        if (eglSurface == null) {
            throw RuntimeException("EGL pbuffer surface was null")
        }
    }

    private fun makeCurrent() {
        val egl = this.egl ?: throw RuntimeException("not configured for makeCurrent")
        checkEglError("before makeCurrent")
        if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    private fun checkEglError(msg: String) {
        val egl = this.egl ?: return
        var failed = false
        var error = egl.eglGetError()
        while (error != EGL10.EGL_SUCCESS) {
            Log.e(TAG, "$msg: EGL error: 0x${Integer.toHexString(error)}")
            failed = true
            error = egl.eglGetError()
        }
        if (failed) throw RuntimeException("EGL error encountered (see log)")
    }

    companion object {
        private const val TAG = "OutputSurface"
        private const val EGL_OPENGL_ES2_BIT = 4
    }
}

/**
 * Renders a SurfaceTexture (GL_TEXTURE_EXTERNAL_OES) onto the current GL framebuffer
 * via a textured quad. Used by [OutputSurface] to upscale/rotate decoded video frames
 * into a pbuffer suitable for `glReadPixels`.
 */
private class TextureRender(flipX: Boolean) {

    private val triangleVertices: FloatBuffer

    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)

    private var program = 0
    var textureId = -12345
        private set
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0

    init {
        val vertices = if (flipX) VERTICES_FLIPPED_X else VERTICES
        triangleVertices = ByteBuffer
            .allocateDirect(vertices.size * FLOAT_SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        triangleVertices.put(vertices).position(0)
        Matrix.setIdentityM(stMatrix, 0)
    }

    fun drawFrame(st: SurfaceTexture) {
        checkGlError("onDrawFrame start")
        st.getTransformMatrix(stMatrix)

        GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        checkGlError("glUseProgram")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        triangleVertices.position(POS_OFFSET)
        GLES20.glVertexAttribPointer(
            maPositionHandle, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, triangleVertices,
        )
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")

        triangleVertices.position(UV_OFFSET)
        GLES20.glVertexAttribPointer(
            maTextureHandle, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, triangleVertices,
        )
        checkGlError("glVertexAttribPointer maTextureHandle")
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")

        Matrix.setIdentityM(mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, stMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGlError("glDrawArrays")
        GLES20.glFinish()
    }

    fun surfaceCreated() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) throw RuntimeException("failed creating program")

        maPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        checkGlError("glGetAttribLocation aPosition")
        if (maPositionHandle == -1) throw RuntimeException("Could not get attrib location for aPosition")

        maTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        checkGlError("glGetAttribLocation aTextureCoord")
        if (maTextureHandle == -1) throw RuntimeException("Could not get attrib location for aTextureCoord")

        muMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        checkGlError("glGetUniformLocation uMVPMatrix")
        if (muMVPMatrixHandle == -1) throw RuntimeException("Could not get uniform location for uMVPMatrix")

        muSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        checkGlError("glGetUniformLocation uSTMatrix")
        if (muSTMatrixHandle == -1) throw RuntimeException("Could not get uniform location for uSTMatrix")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        checkGlError("glBindTexture textureId")

        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGlError("glTexParameter")
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        checkGlError("glCreateShader type=$shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader $shaderType: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            shader = 0
        }
        return shader
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) return 0

        var program = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")
        if (program == 0) Log.e(TAG, "Could not create program")

        GLES20.glAttachShader(program, vertexShader); checkGlError("glAttachShader vs")
        GLES20.glAttachShader(program, fragmentShader); checkGlError("glAttachShader fs")
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            program = 0
        }
        return program
    }

    companion object {
        private const val TAG = "TextureRender"
        private const val FLOAT_SIZE_BYTES = 4
        private const val STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
        private const val POS_OFFSET = 0
        private const val UV_OFFSET = 3

        private val VERTICES = floatArrayOf(
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0f, 0f, 0f,
            1.0f, -1.0f, 0f, 1f, 0f,
            -1.0f, 1.0f, 0f, 0f, 1f,
            1.0f, 1.0f, 0f, 1f, 1f,
        )

        private val VERTICES_FLIPPED_X = floatArrayOf(
            -1.0f, -1.0f, 0f, 1f, 0f,
            1.0f, -1.0f, 0f, 0f, 0f,
            -1.0f, 1.0f, 0f, 1f, 1f,
            1.0f, 1.0f, 0f, 0f, 1f,
        )

        private const val VERTEX_SHADER = "" +
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "  gl_Position = uMVPMatrix * aPosition;\n" +
            "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n"

        private const val FRAGMENT_SHADER = "" +
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n"

        fun checkGlError(msg: String) {
            var failed = false
            var error = GLES20.glGetError()
            while (error != GLES20.GL_NO_ERROR) {
                Log.e(TAG, "$msg: GLES20 error: 0x${Integer.toHexString(error)}")
                failed = true
                error = GLES20.glGetError()
            }
            if (failed) throw RuntimeException("GLES20 error encountered (see log)")
        }
    }
}
