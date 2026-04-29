package id.homebase.imageeditor.ui.widget

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
 * Layout follows Signal's "peek" UX:
 *  - At rest, the slider is shifted left by half its width, so only its
 *    right half is visible at the screen's left edge.
 *  - On first-pointer-down, the slider animates 36 dp to the right
 *    (matching Signal's `animateWidthSeekbarIn` translation), revealing the
 *    full slider while the user drags.
 *  - On gesture release/cancel, it slides back.
 *
 * Percent semantics: `1f` is the top (wide / thick), `0f` is the bottom.
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
    var pressed by remember { mutableStateOf(false) }

    val animatedOffsetX by animateDpAsState(
        targetValue = if (pressed) ACTIVE_OFFSET_X else REST_OFFSET_X,
        animationSpec = tween(durationMillis = SLIDE_DURATION_MS),
        label = "BrushWidthSliderOffset",
    )

    fun positionToPercent(y: Float): Float {
        if (heightPx <= 0f) return percent
        return (1f - (y / heightPx).coerceIn(0f, 1f))
    }

    Canvas(
        modifier = modifier
            .offset(x = animatedOffsetX)
            .height(height)
            .width(width)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onPercentChange(positionToPercent(first.position.y))
                    first.consume()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.pressed } ?: break
                            onPercentChange(positionToPercent(change.position.y))
                            change.consume()
                        }
                    } finally {
                        pressed = false
                    }
                }
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

/** Half the slider width — slider centerline lands on the canvas's left edge. */
private val REST_OFFSET_X: Dp = (-24).dp

/** Resting offset + Signal's 36 dp slide-in translation. */
private val ACTIVE_OFFSET_X: Dp = 12.dp

/** Matches Signal's `ImageEditorHudV2.ANIMATION_DURATION`. */
private const val SLIDE_DURATION_MS: Int = 250
