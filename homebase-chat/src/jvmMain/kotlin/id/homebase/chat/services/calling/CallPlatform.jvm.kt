package id.homebase.chat.services.calling

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
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

actual fun audioRouteChanges(): Flow<List<AudioRoute>> = flowOf(emptyList())
