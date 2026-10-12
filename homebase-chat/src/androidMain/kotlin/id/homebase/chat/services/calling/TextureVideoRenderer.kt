package id.homebase.chat.services.calling

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.ThreadUtils
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch

/** Aspect-fill WebRTC renderer on a TextureView, so a Compose clip can round it. */
internal class TextureVideoRenderer(context: Context, eglContext: EglBase.Context) :
    TextureView(context), TextureView.SurfaceTextureListener, VideoSink {

    private val egl = EglRenderer("CallTextureVideo").apply { init(eglContext, EglBase.CONFIG_PLAIN, GlRectDrawer()) }

    init {
        surfaceTextureListener = this
    }

    fun setMirror(mirror: Boolean) = egl.setMirror(mirror)

    fun release() = egl.release()

    override fun onFrame(frame: VideoFrame) = egl.onFrame(frame)

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        fillTo(width, height)
        egl.createEglSurface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = fillTo(width, height)

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // The GL thread must let go of the texture before the view frees it.
        val released = CountDownLatch(1)
        egl.releaseEglSurface(released::countDown)
        ThreadUtils.awaitUninterruptibly(released)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    // EglRenderer crops each frame to this ratio, which is aspect-fill.
    private fun fillTo(width: Int, height: Int) {
        if (width > 0 && height > 0) egl.setLayoutAspectRatio(width.toFloat() / height)
    }
}
