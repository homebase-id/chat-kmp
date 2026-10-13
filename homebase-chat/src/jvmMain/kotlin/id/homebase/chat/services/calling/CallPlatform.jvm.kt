package id.homebase.chat.services.calling

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

actual fun platformCallEngineFactory(): CallEngineFactory? = CallEngineFactory { WebRtcJavaCallEngine(it) }

@Composable
actual fun CallVideoView(handle: VideoHandle?, modifier: Modifier, cornerRadius: Dp) {
    val video = handle as? JvmVideoHandle ?: return
    val frame = video.frame.collectAsState().value ?: return
    Canvas(modifier.clip(RoundedCornerShape(cornerRadius)).fillMaxSize()) {
        val fill = maxOf(size.width / frame.width, size.height / frame.height)
        val drawn = Size(frame.width * fill, frame.height * fill)
        val dx = ((size.width - drawn.width) / 2).toInt()
        val dy = ((size.height - drawn.height) / 2).toInt()
        scale(if (video.mirrored) -1f else 1f, 1f) {
            drawImage(
                image = frame,
                srcSize = IntSize(frame.width, frame.height),
                dstOffset = IntOffset(dx, dy),
                dstSize = IntSize(drawn.width.toInt(), drawn.height.toInt()),
            )
        }
    }
}

actual fun platformAudioRoutes(): List<AudioRoute> = emptyList()

@Composable
actual fun rememberScreenReaderOn(): Boolean = false

actual class CallVideoRenderers actual constructor() {
    actual fun keepOnly(handles: List<VideoHandle>) = Unit
}
