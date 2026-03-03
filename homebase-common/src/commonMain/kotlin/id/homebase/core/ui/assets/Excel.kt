package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val HomebaseIcons.Excel: ImageVector
    get() {
        if (_Excel != null) {
            return _Excel!!
        }
        _Excel =
            ImageVector.Builder(
                name = "Excel",
                defaultWidth = 384.dp,
                defaultHeight = 512.dp,
                viewportWidth = 384f,
                viewportHeight = 512f
            )
                .apply {
                    path(fill = SolidColor(Color.Black)) {
                        moveTo(64f, 0f)
                        curveTo(28.7f, 0f, 0f, 28.7f, 0f, 64f)
                        verticalLineToRelative(384f)
                        curveToRelative(0f, 35.3f, 28.7f, 64f, 64f, 64f)
                        horizontalLineToRelative(256f)
                        curveToRelative(35.3f, 0f, 64f, -28.7f, 64f, -64f)
                        verticalLineToRelative(-288f)
                        horizontalLineToRelative(-128f)
                        curveToRelative(-17.7f, 0f, -32f, -14.3f, -32f, -32f)
                        lineTo(224f, 0f)
                        lineTo(64f, 0f)
                        close()

                        moveTo(256f, 0f)
                        verticalLineToRelative(128f)
                        horizontalLineToRelative(128f)
                        lineTo(256f, 0f)
                        close()

                        moveTo(155.7f, 250.2f)
                        lineTo(192f, 302.1f)
                        lineToRelative(36.3f, -51.9f)
                        curveToRelative(7.6f, -10.9f, 22.6f, -13.5f, 33.4f, -5.9f)
                        reflectiveCurveToRelative(13.5f, 22.6f, 5.9f, 33.4f)
                        lineTo(221.3f, 344f)
                        lineToRelative(46.4f, 66.2f)
                        curveToRelative(7.6f, 10.9f, 5f, 25.8f, -5.9f, 33.4f)
                        reflectiveCurveToRelative(-25.8f, 5f, -33.4f, -5.9f)
                        lineTo(192f, 385.8f)
                        lineToRelative(-36.3f, 51.9f)
                        curveToRelative(-7.6f, 10.9f, -22.6f, 13.5f, -33.4f, 5.9f)
                        reflectiveCurveToRelative(-13.5f, -22.6f, -5.9f, -33.4f)
                        lineTo(162.7f, 344f)
                        lineToRelative(-46.4f, -66.2f)
                        curveToRelative(-7.6f, -10.9f, -5f, -25.8f, 5.9f, -33.4f)
                        reflectiveCurveToRelative(25.8f, -5f, 33.4f, 5.9f)
                        close()
                    }
                }
                .build()

        return _Excel!!
    }

@Suppress("ObjectPropertyName")
private var _Excel: ImageVector? = null
