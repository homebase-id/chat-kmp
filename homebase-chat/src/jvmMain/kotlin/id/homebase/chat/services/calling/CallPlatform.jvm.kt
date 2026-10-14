package id.homebase.chat.services.calling

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle

actual fun platformCallEngineFactory(): CallEngineFactory? = CallEngineFactory { WebRtcJavaCallEngine(it, devicePreferences = desktopCallDevicePreferences()) }

@Composable
actual fun CallVideoView(handle: VideoHandle?, modifier: Modifier, cornerRadius: Dp) {
    val video = handle as? JvmVideoHandle ?: return
    // Read in the draw phase: each frame redraws the canvas without recomposing.
    val frameState = video.frame.collectAsStateWithLifecycle()
    Canvas(modifier.clip(RoundedCornerShape(cornerRadius)).fillMaxSize()) {
        val frame = frameState.value ?: return@Canvas
        val width = frame.image.width
        val height = frame.image.height
        val sideways = frame.rotation % 180 != 0
        val fill = maxOf(size.width / (if (sideways) height else width), size.height / (if (sideways) width else height))
        val drawn = Size(width * fill, height * fill)
        val dx = ((size.width - drawn.width) / 2).toInt()
        val dy = ((size.height - drawn.height) / 2).toInt()
        scale(if (video.mirrored) -1f else 1f, 1f) {
            rotate(frame.rotation.toFloat()) {
                video.draw(frame) { image ->
                    drawImage(
                        image = image,
                        srcSize = IntSize(width, height),
                        dstOffset = IntOffset(dx, dy),
                        dstSize = IntSize(drawn.width.toInt(), drawn.height.toInt()),
                    )
                }
            }
        }
    }
}

actual fun platformAudioRoutes(): List<AudioRoute> = emptyList()

@Composable
actual fun rememberScreenReaderOn(): Boolean = false

actual class CallVideoRenderers actual constructor() {
    actual fun keepOnly(handles: List<VideoHandle>) = Unit
}
