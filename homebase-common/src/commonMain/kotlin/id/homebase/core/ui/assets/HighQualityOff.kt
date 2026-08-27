package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Material ships no high_quality_off / hd_off. Enclosing box, gutter and slash are transcribed
// from Filled.ClosedCaptionDisabled, whose box is the same 3-21 x 4-20 r2 rect as Filled.HighQuality.
val HomebaseIcons.HighQualityOff: ImageVector
    get() {
        if (_HighQualityOff != null) {
            return _HighQualityOff!!
        }
        _HighQualityOff = ImageVector.Builder(
            name = "HighQualityOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6.83f, 4f)
                horizontalLineTo(19f)
                curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
                verticalLineToRelative(12f)
                curveToRelative(0f, 0.05f, -0.01f, 0.1f, -0.02f, 0.16f)
                lineToRelative(-3.38f, -3.38f)
                curveTo(17.84f, 14.59f, 18f, 14.32f, 18f, 14f)
                verticalLineTo(10f)
                curveTo(18f, 9.45f, 17.55f, 9f, 17f, 9f)
                horizontalLineTo(14f)
                curveTo(13.45f, 9f, 13f, 9.45f, 13f, 10f)
                verticalLineToRelative(0.17f)
                lineTo(6.83f, 4f)
                close()
                moveTo(16.33f, 13.5f)
                horizontalLineTo(16.5f)
                verticalLineTo(10.5f)
                horizontalLineTo(14.5f)
                verticalLineToRelative(1.17f)
                close()
                moveTo(19.78f, 22.61f)
                lineTo(17.17f, 20f)
                horizontalLineTo(5f)
                curveToRelative(-1.11f, 0f, -2f, -0.9f, -2f, -2f)
                verticalLineTo(6f)
                curveToRelative(0f, -0.05f, 0.02f, -0.1f, 0.02f, -0.15f)
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

        return _HighQualityOff!!
    }

@Suppress("ObjectPropertyName")
private var _HighQualityOff: ImageVector? = null
