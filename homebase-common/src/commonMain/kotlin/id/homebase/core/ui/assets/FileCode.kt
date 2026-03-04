package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val HomebaseIcons.FileCode: ImageVector
    get() {
        if (_FileCode != null) {
            return _FileCode!!
        }
        _FileCode =
            ImageVector.Builder(
                name = "FileCode",
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

                        moveTo(153f, 289f)
                        lineToRelative(-31f, 31f)
                        lineToRelative(31f, 31f)
                        curveToRelative(9.4f, 9.4f, 9.4f, 24.6f, 0f, 33.9f)
                        reflectiveCurveToRelative(-24.6f, 9.4f, -33.9f, 0f)
                        lineTo(71f, 337f)
                        curveToRelative(-9.4f, -9.4f, -9.4f, -24.6f, 0f, -33.9f)
                        lineToRelative(48f, -48f)
                        curveToRelative(9.4f, -9.4f, 24.6f, -9.4f, 33.9f, 0f)
                        reflectiveCurveToRelative(9.4f, 24.6f, 0f, 33.9f)
                        close()

                        moveTo(265f, 255f)
                        lineToRelative(48f, 48f)
                        curveToRelative(9.4f, 9.4f, 9.4f, 24.6f, 0f, 33.9f)
                        lineToRelative(-48f, 48f)
                        curveToRelative(-9.4f, 9.4f, -24.6f, 9.4f, -33.9f, 0f)
                        reflectiveCurveToRelative(-9.4f, -24.6f, 0f, -33.9f)
                        lineToRelative(31f, -31f)
                        lineToRelative(-31f, -31f)
                        curveToRelative(-9.4f, -9.4f, -9.4f, -24.6f, 0f, -33.9f)
                        reflectiveCurveToRelative(24.6f, -9.4f, 33.9f, 0f)
                        close()
                    }
                }
                .build()

        return _FileCode!!
    }

@Suppress("ObjectPropertyName")
private var _FileCode: ImageVector? = null
