package id.homebase.imageeditor.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Vertical brush-width picker styled after Signal's `widthSeekBar`.
 * Renders a white-translucent triangle that's narrow at the bottom and wide
 * at the top, with a small white thumb that the user drags up to thicken the
 * stroke (or down to thin it).
 *
 * The slider overlays the draw canvas — strokes that start outside the
 * slider's hit area still reach the canvas underneath. Pointer events that
 * land inside are consumed.
 *
 * Percent semantics: `1f` is the top (wide / thick), `0f` is the bottom
 * (narrow / thin). Matches the user's intuition and Signal's drawable
 * orientation.
 */
@Composable
fun BrushWidthSlider(
    percent: Float,
    onPercentChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = SLIDER_HEIGHT,
    width: Dp = SLIDER_WIDTH,
) {
    var heightPx by remember { mutableFloatStateOf(0f) }

    fun positionToPercent(y: Float): Float {
        if (heightPx <= 0f) return percent
        return (1f - (y / heightPx).coerceIn(0f, 1f))
    }

    Canvas(
        modifier = modifier
            .height(height)
            .width(width)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onPercentChange(positionToPercent(change.position.y))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset -> onPercentChange(positionToPercent(offset.y)) },
                )
            },
    ) {
        heightPx = size.height

        val w = size.width
        val h = size.height
        // Triangle: wide at top (full width), tapering to a needle at the
        // bottom. Centered on the slider's vertical axis.
        val tipNarrowHalf = 0.5.dp.toPx()
        val centerX = w / 2f
        val triangle = Path().apply {
            moveTo(0f, 0f)
            lineTo(w, 0f)
            lineTo(centerX + tipNarrowHalf, h)
            lineTo(centerX - tipNarrowHalf, h)
            close()
        }
        drawPath(triangle, color = Color.White.copy(alpha = 0.6f), style = Fill)

        val thumbRadius = THUMB_RADIUS.toPx()
        val travel = (h - thumbRadius * 2f).coerceAtLeast(0f)
        val cy = (1f - percent.coerceIn(0f, 1f)) * travel + thumbRadius
        drawCircle(color = Color.White, radius = thumbRadius, center = Offset(centerX, cy))
    }
}

private val SLIDER_HEIGHT: Dp = 174.dp
private val SLIDER_WIDTH: Dp = 48.dp
private val THUMB_RADIUS: Dp = 10.dp
