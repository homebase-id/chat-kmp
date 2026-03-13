package id.homebase.core.shapes

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * A squircle shape that smoothly blends between a square and a circle.
 * 
 * @param cornerSmoothing The smoothing factor (0.0 to 1.0). 
 * Higher values create more rounded corners, closer to a superellipse.
 * Default is 0.6 which creates a pleasant squircle shape.
 */
class SquircleShape(
    private val cornerSmoothing: Float = 0.6f
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = Path().apply {
            squirclePath(size)
        }
        return Outline.Generic(path)
    }

    private fun Path.squirclePath(size: Size) {
        val width = size.width
        val height = size.height

        // Use superellipse formula for squircle
        // x^n + y^n = r^n, where n determines the shape
        val n = 2.0 + (cornerSmoothing * 3.0) // Range from 2 (diamond-like) to 5 (more circular)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = minOf(centerX, centerY)

        moveTo(centerX, 0f)

        // Create the squircle by calculating points along the curve
        val steps = 360
        for (i in 0..steps) {
            val angle = (i * PI * 2 / steps)
            val cosAngle = cos(angle)
            val sinAngle = sin(angle)

            // Superellipse formula: r(θ) = (|cos(θ)|^n + |sin(θ)|^n)^(-1/n)
            val r = (abs(cosAngle).pow(n) + abs(sinAngle).pow(n)).pow(-1.0 / n).toFloat()

            val x = centerX + r * radius * cosAngle.toFloat()
            val y = centerY + r * radius * sinAngle.toFloat()

            if (i == 0) {
                moveTo(x, y)
            } else {
                lineTo(x, y)
            }
        }

        close()
    }
}


