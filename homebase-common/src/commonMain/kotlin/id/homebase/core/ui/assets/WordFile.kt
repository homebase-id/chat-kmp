package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val HomebaseIcons.WordFile: ImageVector
    get() {
        if (_WordFile != null) {
            return _WordFile!!
        }
        _WordFile =
            ImageVector.Builder(
                name = "WordFile",
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

                        moveTo(111f, 257.1f)
                        lineToRelative(26.8f, 89.2f)
                        lineToRelative(31.6f, -90.3f)
                        curveToRelative(3.4f, -9.6f, 12.5f, -16.1f, 22.7f, -16.1f)
                        reflectiveCurveToRelative(19.3f, 6.4f, 22.7f, 16.1f)
                        lineToRelative(31.6f, 90.3f)
                        lineTo(273f, 257.1f)
                        curveToRelative(3.8f, -12.7f, 17.2f, -19.9f, 29.9f, -16.1f)
                        reflectiveCurveToRelative(19.9f, 17.2f, 16.1f, 29.9f)
                        lineToRelative(-48f, 160f)
                        curveToRelative(-3f, 10f, -12f, 16.9f, -22.4f, 17.1f)
                        reflectiveCurveToRelative(-19.8f, -6.2f, -23.2f, -16.1f)
                        lineTo(192f, 336.6f)
                        lineToRelative(-33.3f, 95.3f)
                        curveToRelative(-3.4f, 9.8f, -12.8f, 16.3f, -23.2f, 16.1f)
                        reflectiveCurveToRelative(-19.5f, -7.1f, -22.4f, -17.1f)
                        lineToRelative(-48f, -160f)
                        curveToRelative(-3.8f, -12.7f, 3.4f, -26.1f, 16.1f, -29.9f)
                        reflectiveCurveToRelative(26.1f, 3.4f, 29.9f, 16.1f)
                        close()
                    }
                }
                .build()

        return _WordFile!!
    }

@Suppress("ObjectPropertyName")
private var _WordFile: ImageVector? = null
