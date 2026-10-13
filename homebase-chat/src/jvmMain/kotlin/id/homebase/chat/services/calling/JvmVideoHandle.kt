package id.homebase.chat.services.calling

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoFrameBuffer
import dev.onvoid.webrtc.media.video.VideoTrackSink
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.impl.BufferUtil

/** One decoded frame; [rotation] is the clockwise turn, in degrees, that shows it upright. */
class VideoFrameImage internal constructor(internal val bitmap: Bitmap, val image: ImageBitmap, val rotation: Int)

class JvmVideoHandle(mirrored: Boolean = false) : VideoHandle {
    var mirrored by mutableStateOf(mirrored)
    private val _frame = MutableStateFlow<VideoFrameImage?>(null)
    val frame: StateFlow<VideoFrameImage?> = _frame.asStateFlow()

    // One bitmap per frame size, written in place: the sink converts into it and a draw copies out of it under this lock.
    private val pixels = Any()
    private var bitmap: Bitmap? = null
    private var image: ImageBitmap? = null
    private var target: ByteBuffer? = null
    private var closed = false
    private var lastDrawNs = 0L

    // webrtc-java hands every sink its own reference to the native buffer and has no finalizer: an unreleased frame leaks for good.
    val sink = VideoTrackSink { videoFrame: VideoFrame ->
        try {
            render(videoFrame)
        } finally {
            videoFrame.release()
        }
    }

    private fun render(videoFrame: VideoFrame) {
        // ~20 fps is plenty for a call tile and keeps the conversion off the hot path.
        val now = System.nanoTime()
        if (now - lastDrawNs < MIN_FRAME_GAP_NS) return
        lastDrawNs = now
        synchronized(pixels) {
            if (closed) return
            val buffer = videoFrame.buffer
            val out = targetFor(buffer)
            if (runCatching { VideoBufferConverter.convertFromI420(buffer, out.rewind(), FourCC.ARGB) }.isFailure) return
            val bitmap = bitmap!!.notifyPixelsChanged()
            _frame.value = VideoFrameImage(bitmap, image!!, videoFrame.rotation)
        }
    }

    private fun targetFor(buffer: VideoFrameBuffer): ByteBuffer {
        val current = bitmap
        if (current != null && current.width == buffer.width && current.height == buffer.height) return target!!
        current?.close()
        val next = Bitmap().apply { allocPixels(ImageInfo(buffer.width, buffer.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)) }
        val out = next.peekPixels()!!.use { BufferUtil.getByteBufferFromPointer(it.addr, it.rowBytes * buffer.height) }
        bitmap = next
        image = next.asComposeImageBitmap()
        target = out
        return out
    }

    /** Runs [block] while [frame]'s pixels hold still; a frame whose bitmap was resized away or closed is skipped. */
    fun draw(frame: VideoFrameImage, block: (ImageBitmap) -> Unit) = synchronized(pixels) {
        if (!frame.bitmap.isClosed) block(frame.image)
    }

    fun close() = synchronized(pixels) {
        closed = true
        _frame.value = null
        bitmap?.close()
        bitmap = null
        image = null
        target = null
    }

    companion object {
        private const val MIN_FRAME_GAP_NS = 50_000_000L
    }
}
