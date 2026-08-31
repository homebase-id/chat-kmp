package id.homebase.imageeditor.ui.widget

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

internal val ColorSliderStops: Array<Pair<Float, Color>> = arrayOf(
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

// Lerp sRGB channels, never HSV: the gradient shader blends stops in RGB, and any other
// interpolation space puts the picked colour back off the bar.
internal fun colorForPosition(position: Float): Int {
    val p = position.coerceIn(0f, 1f)
    var i = 0
    while (i < ColorSliderStops.size - 1 && p > ColorSliderStops[i + 1].first) i++
    val (startPos, startColor) = ColorSliderStops[i]
    val (endPos, endColor) = ColorSliderStops[i + 1]
    val span = endPos - startPos
    val t = if (span <= 0f) 0f else ((p - startPos) / span).coerceIn(0f, 1f)
    return argb(
        channel(startColor.red, endColor.red, t),
        channel(startColor.green, endColor.green, t),
        channel(startColor.blue, endColor.blue, t),
    )
}

private fun channel(from: Float, to: Float, t: Float): Int =
    ((from + (to - from) * t) * 255f).roundToInt().coerceIn(0, 255)

private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
