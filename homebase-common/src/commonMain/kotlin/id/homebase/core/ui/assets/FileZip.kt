package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val HomebaseIcons.FileZip: ImageVector
    get() {
        if (_FileZip != null) {
            return _FileZip!!
        }
        _FileZip =
            ImageVector.Builder(
                name = "FileZip",
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

                        moveTo(96f, 48f)
                        curveToRelative(0f, -8.8f, 7.2f, -16f, 16f, -16f)
                        horizontalLineToRelative(32f)
                        curveToRelative(8.8f, 0f, 16f, 7.2f, 16f, 16f)
                        reflectiveCurveToRelative(-7.2f, 16f, -16f, 16f)
                        horizontalLineToRelative(-32f)
                        curveToRelative(-8.8f, 0f, -16f, -7.2f, -16f, -16f)
                        close()

                        moveTo(96f, 112f)
                        curveToRelative(0f, -8.8f, 7.2f, -16f, 16f, -16f)
                        horizontalLineToRelative(32f)
                        curveToRelative(8.8f, 0f, 16f, 7.2f, 16f, 16f)
                        reflectiveCurveToRelative(-7.2f, 16f, -16f, 16f)
                        horizontalLineToRelative(-32f)
                        curveToRelative(-8.8f, 0f, -16f, -7.2f, -16f, -16f)
                        close()

                        moveTo(96f, 176f)
                        curveToRelative(0f, -8.8f, 7.2f, -16f, 16f, -16f)
                        horizontalLineToRelative(32f)
                        curveToRelative(8.8f, 0f, 16f, 7.2f, 16f, 16f)
                        reflectiveCurveToRelative(-7.2f, 16f, -16f, 16f)
                        horizontalLineToRelative(-32f)
                        curveToRelative(-8.8f, 0f, -16f, -7.2f, -16f, -16f)
                        close()

                        moveToRelative(-6.3f, 71.8f)
                        curveToRelative(3.7f, -14f, 16.4f, -23.8f, 30.9f, -23.8f)
                        horizontalLineToRelative(14.8f)
                        curveToRelative(14.5f, 0f, 27.2f, 9.7f, 30.9f, 23.8f)
                        lineToRelative(23.5f, 88.2f)
                        curveToRelative(1.4f, 5.4f, 2.1f, 10.9f, 2.1f, 16.4f)
                        curveToRelative(0f, 35.2f, -28.8f, 63.7f, -64f, 63.7f)
                        reflectiveCurveToRelative(-64f, -28.5f, -64f, -63.7f)
                        curveToRelative(0f, -5.5f, 0.7f, -11.1f, 2.1f, -16.4f)
                        lineToRelative(23.5f, -88.2f)
                        close()

                        moveTo(112f, 336f)
                        curveToRelative(-8.8f, 0f, -16f, 7.2f, -16f, 16f)
                        reflectiveCurveToRelative(7.2f, 16f, 16f, 16f)
                        horizontalLineToRelative(32f)
                        curveToRelative(8.8f, 0f, 16f, -7.2f, 16f, -16f)
                        reflectiveCurveToRelative(-7.2f, -16f, -16f, -16f)
                        horizontalLineToRelative(-32f)
                        close()
                    }
                }
                .build()

        return _FileZip!!
    }

@Suppress("ObjectPropertyName")
private var _FileZip: ImageVector? = null
