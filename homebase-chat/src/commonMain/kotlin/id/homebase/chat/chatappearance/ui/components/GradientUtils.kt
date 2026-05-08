package id.homebase.chat.chatappearance.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Creates a [Brush.linearGradient] whose direction is controlled by [angleDegrees].
 *
 * Angle convention (matches CSS / Signal):
 * - 0° = left-to-right
 * - 90° = top-to-bottom
 * - 180° = right-to-left
 *
 * The start/end offsets are projected to a large coordinate space so the
 * gradient always spans the full composable regardless of its actual size.
 */
fun angledLinearGradient(colors: List<Color>, angleDegrees: Float): Brush {
    val angleRad = angleDegrees * (PI.toFloat() / 180f)
    val x = cos(angleRad)
    val y = sin(angleRad)
    // Use a large value so the gradient always fills the composable.
    val far = 2000f
    return Brush.linearGradient(
        colors = colors,
        start = Offset(far * (0.5f - x * 0.5f), far * (0.5f - y * 0.5f)),
        end = Offset(far * (0.5f + x * 0.5f), far * (0.5f + y * 0.5f)),
    )
}
