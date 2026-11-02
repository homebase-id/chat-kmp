package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The owner console's Apps icon: a 2x2 grid of rounded squares. */
val HomebaseIcons.Apps: ImageVector
    get() {
        _Apps?.let { return it }
        return ImageVector.Builder(
            name = "Apps",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                square(3f, 3f)
                square(13.5f, 3f)
                square(3f, 13.5f)
                square(13.5f, 13.5f)
            }
        }.build().also { _Apps = it }
    }

private const val Side = 7.5f
private const val Radius = 2f

private fun PathBuilder.square(x: Float, y: Float) {
    moveTo(x + Radius, y)
    horizontalLineTo(x + Side - Radius)
    arcTo(Radius, Radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x + Side, y + Radius)
    verticalLineTo(y + Side - Radius)
    arcTo(Radius, Radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x + Side - Radius, y + Side)
    horizontalLineTo(x + Radius)
    arcTo(Radius, Radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x, y + Side - Radius)
    verticalLineTo(y + Radius)
    arcTo(Radius, Radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x + Radius, y)
    close()
}

@Suppress("ObjectPropertyName")
private var _Apps: ImageVector? = null
