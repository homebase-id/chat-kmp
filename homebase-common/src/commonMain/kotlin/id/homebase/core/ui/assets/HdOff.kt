package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Material ships no hd_off. Filled.Hd clipped against Material's standard *Off slash: bar between
// y=x and y=x+2.83, upper-right pieces cut back to y=x-2.83, lower-left pieces fused to the bar.
val HomebaseIcons.HdOff: ImageVector
    get() {
        if (_HdOff != null) {
            return _HdOff!!
        }
        _HdOff = ImageVector.Builder(
            name = "HdOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(5.83f, 3f)
                horizontalLineTo(19f)
                curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
                verticalLineTo(18.17f)
                lineTo(17.62f, 14.79f)
                curveTo(17.85f, 14.6f, 18f, 14.32f, 18f, 14f)
                verticalLineTo(10f)
                curveTo(18f, 9.45f, 17.55f, 9f, 17f, 9f)
                horizontalLineTo(13f)
                verticalLineTo(10.17f)
                lineTo(5.83f, 3f)
                close()
                moveTo(16.33f, 13.5f)
                horizontalLineTo(16.5f)
                verticalLineTo(10.5f)
                horizontalLineTo(14.5f)
                verticalLineToRelative(1.17f)
                close()
                moveTo(19.78f, 22.61f)
                lineTo(18.17f, 21f)
                horizontalLineTo(5f)
                curveToRelative(-1.11f, 0f, -2f, -0.9f, -2f, -2f)
                verticalLineTo(5.83f)
                lineTo(1.39f, 4.22f)
                lineToRelative(1.41f, -1.41f)
                lineTo(21.18f, 21.19f)
                lineTo(19.78f, 22.61f)
                close()
                moveTo(11f, 15f)
                verticalLineTo(13.83f)
                lineTo(8.67f, 11.5f)
                horizontalLineTo(7.5f)
                verticalLineTo(10.33f)
                lineTo(6.17f, 9f)
                horizontalLineTo(6f)
                verticalLineTo(15f)
                horizontalLineTo(7.5f)
                verticalLineTo(13f)
                horizontalLineTo(9.5f)
                verticalLineTo(15f)
                close()
            }
        }.build()

        return _HdOff!!
    }

@Suppress("ObjectPropertyName")
private var _HdOff: ImageVector? = null
