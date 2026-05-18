package id.homebase.api.video.transcoder_v2.internal.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import id.homebase.api.video.transcoder_v2.GlPipelineException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Compiles a pass-through GLES 2.0 program and renders the contents of a
 * SurfaceTexture (bound to a `GL_TEXTURE_EXTERNAL_OES` texture) onto the
 * currently-current GL framebuffer via a textured quad.
 *
 * Used by [OutputSurface] to bridge decoder output → encoder input. Scale
 * conversion is implicit: the quad fills the viewport (= encoder dims);
 * `GL_LINEAR` minification + magnification gives bilinear sampling, much
 * better than our previous nearest-neighbor YUV copy.
 *
 * Ported from Signal-Android's `transcoder/videoconverter/TextureRender.java`
 * (AOSP-licensed). The [flipX] option was dropped — rotation lives in
 * container metadata (`tkhd` via `MediaMuxer.setOrientationHint`), not in
 * pixel data.
 *
 * The [viewportW] / [viewportH] constructor params set the GL viewport
 * explicitly in [surfaceCreated] as a safety net — Signal relies on the
 * EGL window surface defaulting to the encoder dims, which is true but
 * not documented.
 */
internal class TextureRender(
    private val viewportW: Int,
    private val viewportH: Int,
) {

    private val triangleVertices: FloatBuffer = ByteBuffer
        .allocateDirect(VERTICES.size * FLOAT_SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(VERTICES).position(0) }

    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var program = 0
    var textureId: Int = -12345
        private set
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0

    /** Call after the EGL surface is current. */
    fun surfaceCreated() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) throw GlPipelineException("failed creating GL program")

        maPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        checkGlError("glGetAttribLocation aPosition")
        if (maPositionHandle == -1) throw GlPipelineException("no attrib location aPosition")

        maTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        checkGlError("glGetAttribLocation aTextureCoord")
        if (maTextureHandle == -1) throw GlPipelineException("no attrib location aTextureCoord")

        muMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        checkGlError("glGetUniformLocation uMVPMatrix")
        if (muMVPMatrixHandle == -1) throw GlPipelineException("no uniform location uMVPMatrix")

        muSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
        checkGlError("glGetUniformLocation uSTMatrix")
        if (muSTMatrixHandle == -1) throw GlPipelineException("no uniform location uSTMatrix")

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

        GLES20.glViewport(0, 0, viewportW, viewportH)
        checkGlError("glViewport")
    }

    fun drawFrame(st: SurfaceTexture) {
        checkGlError("onDrawFrame start")
        st.getTransformMatrix(stMatrix)

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        checkGlError("glUseProgram")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        triangleVertices.position(POS_OFFSET)
        GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, triangleVertices)
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")

        triangleVertices.position(UV_OFFSET)
        GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, triangleVertices)
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

    /** Replace the fragment shader (e.g. for box-filter downscaling). API hook for a future quality bump. */
    fun changeFragmentShader(fragmentShader: String) {
        GLES20.glDeleteProgram(program)
        program = createProgram(VERTEX_SHADER, fragmentShader)
        if (program == 0) throw GlPipelineException("failed creating GL program (shader swap)")
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vs == 0) return 0
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fs == 0) return 0

        val prog = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")
        if (prog == 0) { Log.e(TAG, "could not create program"); return 0 }
        GLES20.glAttachShader(prog, vs); checkGlError("glAttachShader vs")
        GLES20.glAttachShader(prog, fs); checkGlError("glAttachShader fs")
        GLES20.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "could not link program: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        val shader = GLES20.glCreateShader(shaderType)
        checkGlError("glCreateShader type=$shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "could not compile shader $shaderType: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
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
            -1.0f,  1.0f, 0f, 0f, 1f,
             1.0f,  1.0f, 0f, 1f, 1f,
        )

        private const val VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "  gl_Position = uMVPMatrix * aPosition;\n" +
            "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
            "}\n"

        private const val FRAGMENT_SHADER =
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
                Log.e(TAG, "$msg: GLES20 error 0x${Integer.toHexString(error)}")
                failed = true
                error = GLES20.glGetError()
            }
            if (failed) throw GlPipelineException("GLES20 error (see log)")
        }
    }
}
