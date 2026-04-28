package id.homebase.imageeditor.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Horizontal continuous-color picker styled after Signal's `HSVColorSlider`.
 * The strip runs `black → primary spectrum (full saturation) → white`. Drag
 * or tap to set a position; the parent owns the position state via
 * [position] and [onPositionChange].
 */
@Composable
fun HsvColorSlider(
    position: Float,
    onPositionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableFloatStateOf(0f) }

    val gradient = remember {
        Brush.horizontalGradient(
            0.00f to Color.Black,
            0.05f to Color.Black,
            0.18f to Color.Red,
            0.32f to Color.Yellow,
            0.46f to Color.Green,
            0.60f to Color.Cyan,
            0.74f to Color.Blue,
            0.88f to Color.Magenta,
            0.95f to Color.White,
            1.00f to Color.White,
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, _ ->
                        change.consume()
                        if (width > 0f) {
                            val pos = (change.position.x / width).coerceIn(0f, 1f)
                            onPositionChange(pos)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        if (width > 0f) {
                            val pos = (offset.x / width).coerceIn(0f, 1f)
                            onPositionChange(pos)
                        }
                    },
                )
            },
    ) {
        width = size.width
        drawRect(brush = gradient, size = Size(size.width, size.height))
        // Thumb: a white outlined circle at the current position.
        val cx = (size.width * position.coerceIn(0f, 1f))
        val cy = size.height / 2f
        val radius = size.height * 0.45f
        drawCircle(color = Color.White, radius = radius, center = Offset(cx, cy), style = Stroke(width = 2.5.dp.toPx()))
        drawCircle(color = Color.Black.copy(alpha = 0.45f), radius = radius - 1f, center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
    }
}
