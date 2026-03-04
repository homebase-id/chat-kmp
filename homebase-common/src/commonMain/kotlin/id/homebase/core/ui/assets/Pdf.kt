package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val HomebaseIcons.Pdf: ImageVector
    get() {
        if (_Pdf != null) {
            return _Pdf!!
        }
        _Pdf =
            ImageVector.Builder(
                name = "Pdf",
                defaultWidth = 512.dp,
                defaultHeight = 512.dp,
                viewportWidth = 512f,
                viewportHeight = 512f
            )
                .apply {
                    path(fill = SolidColor(Color.Black)) {
                        moveTo(0f, 64f)
                        curveTo(0f, 28.7f, 28.7f, 0f, 64f, 0f)
                        lineTo(224f, 0f)
                        verticalLineToRelative(128f)
                        curveToRelative(0f, 17.7f, 14.3f, 32f, 32f, 32f)
                        horizontalLineToRelative(128f)
                        verticalLineToRelative(144f)
                        horizontalLineToRelative(-208f)
                        curveToRelative(-35.3f, 0f, -64f, 28.7f, -64f, 64f)
                        verticalLineToRelative(144f)
                        horizontalLineToRelative(-48f)
                        curveToRelative(-35.3f, 0f, -64f, -28.7f, -64f, -64f)
                        lineTo(0f, 64f)
                        close()

                        moveTo(384f, 128f)
                        horizontalLineToRelative(-128f)
                        lineTo(256f, 0f)
                        lineTo(384f, 128f)
                        close()

                        moveTo(176f, 352f)
                        horizontalLineToRelative(32f)
                        curveToRelative(30.9f, 0f, 56f, 25.1f, 56f, 56f)
                        reflectiveCurveToRelative(-25.1f, 56f, -56f, 56f)
                        horizontalLineToRelative(-16f)
                        verticalLineToRelative(32f)
                        curveToRelative(0f, 8.8f, -7.2f, 16f, -16f, 16f)
                        reflectiveCurveToRelative(-16f, -7.2f, -16f, -16f)
                        verticalLineToRelative(-48f)
                        verticalLineToRelative(-80f)
                        curveToRelative(0f, -8.8f, 7.2f, -16f, 16f, -16f)
                        close()

                        moveTo(208f, 432f)
                        curveToRelative(13.3f, 0f, 24f, -10.7f, 24f, -24f)
                        reflectiveCurveToRelative(-10.7f, -24f, -24f, -24f)
                        horizontalLineToRelative(-16f)
                        verticalLineToRelative(48f)
                        horizontalLineToRelative(16f)
                        close()

                        moveTo(304f, 352f)
                        horizontalLineToRelative(32f)
                        curveToRelative(26.5f, 0f, 48f, 21.5f, 48f, 48f)
                        verticalLineToRelative(64f)
                        curveToRelative(0f, 26.5f, -21.5f, 48f, -48f, 48f)
                        horizontalLineToRelative(-32f)
                        curveToRelative(-8.8f, 0f, -16f, -7.2f, -16f, -16f)
                        verticalLineToRelative(-128f)
                        curveToRelative(0f, -8.8f, 7.2f, -16f, 16f, -16f)
                        close()

                        moveTo(336f, 480f)
                        curveToRelative(8.8f, 0f, 16f, -7.2f, 16f, -16f)
                        verticalLineToRelative(-64f)
                        curveToRelative(0f, -8.8f, -7.2f, -16f, -16f, -16f)
                        horizontalLineToRelative(-16f)
                        verticalLineToRelative(96f)
                        horizontalLineToRelative(16f)
                        close()

                        moveTo(416f, 368f)
                        curveToRelative(0f, -8.8f, 7.2f, -16f, 16f, -16f)
                        horizontalLineToRelative(48f)
                        curveToRelative(8.8f, 0f, 16f, 7.2f, 16f, 16f)
                        reflectiveCurveToRelative(-7.2f, 16f, -16f, 16f)
                        horizontalLineToRelative(-32f)
                        verticalLineToRelative(32f)
                        horizontalLineToRelative(32f)
                        curveToRelative(8.8f, 0f, 16f, 7.2f, 16f, 16f)
                        reflectiveCurveToRelative(-7.2f, 16f, -16f, 16f)
                        horizontalLineToRelative(-32f)
                        verticalLineToRelative(48f)
                        curveToRelative(0f, 8.8f, -7.2f, 16f, -16f, 16f)
                        reflectiveCurveToRelative(-16f, -7.2f, -16f, -16f)
                        verticalLineToRelative(-64f)
                        verticalLineToRelative(-64f)
                        close()
                    }
                }
                .build()

        return _Pdf!!
    }

@Suppress("ObjectPropertyName")
private var _Pdf: ImageVector? = null
