package id.homebase.chat.services.calling

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

class JvmVideoHandle(mirrored: Boolean = false) : VideoHandle {
    var mirrored by mutableStateOf(mirrored)
    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    @Volatile private var closed = false
    private var lastDrawNs = 0L

    val sink = VideoTrackSink { videoFrame: VideoFrame ->
        // ~20 fps is plenty for a call tile and keeps the conversion off the hot path.
        val now = System.nanoTime()
        if (closed || now - lastDrawNs < MIN_FRAME_GAP_NS) return@VideoTrackSink
        lastDrawNs = now
        runCatching {
            val buffer = videoFrame.buffer
            val w = buffer.width
            val h = buffer.height
            val bytes = ByteArray(w * h * 4)
            VideoBufferConverter.convertFromI420(buffer, bytes, FourCC.ARGB)
            val bitmap = Bitmap()
            bitmap.allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
            bitmap.installPixels(bytes)
            _frame.value = bitmap.asComposeImageBitmap()
        }
    }

    fun close() {
        closed = true
        _frame.value = null
    }

    companion object {
        private const val MIN_FRAME_GAP_NS = 50_000_000L
    }
}
