package id.homebase.core.ui.assets

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val HomebaseIcons.Homebase: ImageVector
    get() {
        if (_Homebase != null) {
            return _Homebase!!
        }
        _Homebase = ImageVector.Builder(
            name = "Homebase",
            defaultWidth = 2902.7.dp,
            defaultHeight = 2902.7.dp,
            viewportWidth = 2902.7f,
            viewportHeight = 2902.7f
        ).apply {
            path(
                fill = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color(0xFF396281),
                        0f to Color(0xFF415A84),
                        0.2f to Color(0xFF4F4D89),
                        0.3f to Color(0xFF58458D),
                        0.5f to Color(0xFF5B438E),
                        0.7f to Color(0xFF57458C),
                        0.8f to Color(0xFF534A8B),
                        1f to Color(0xFF425985)
                    ),
                    start = Offset(2297.4f, 2548f),
                    end = Offset(-1551.9f, -2441.8f)
                )
            ) {
                moveTo(0f, 0f)
                horizontalLineToRelative(2902.7f)
                verticalLineToRelative(2902.7f)
                horizontalLineToRelative(-2902.7f)
                close()
            }
            path(
                fill = SolidColor(Color.White),
                stroke = SolidColor(Color(0xFF1D1D1B)),
                strokeLineWidth = 1f
            ) {
                moveTo(2018.3f, 635f)
                moveToRelative(-283.5f, 0f)
                arcToRelative(283.5f, 283.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 567f, 0f)
                arcToRelative(283.5f, 283.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, -567f, 0f)
            }
            path(
                fill = SolidColor(Color.White),
                stroke = SolidColor(Color(0xFF1D1D1B)),
                strokeLineWidth = 1f
            ) {
                moveTo(2018.3f, 1167.9f)
                horizontalLineToRelative(-566.9f)
                curveToRelative(-156.6f, 0f, -283.5f, -126.9f, -283.5f, -283.5f)
                horizontalLineToRelative(0f)
                verticalLineToRelative(-249.4f)
                curveToRelative(0f, -156.6f, -126.9f, -283.5f, -283.5f, -283.5f)
                reflectiveCurveToRelative(-283.5f, 126.9f, -283.5f, 283.5f)
                horizontalLineToRelative(0f)
                verticalLineToRelative(1632.8f)
                horizontalLineToRelative(0f)
                curveToRelative(0f, 156.6f, 126.9f, 283.5f, 283.5f, 283.5f)
                reflectiveCurveToRelative(283.5f, -126.9f, 283.5f, -283.5f)
                verticalLineToRelative(-532.9f)
                horizontalLineToRelative(0f)
                curveToRelative(0.2f, -156.4f, 127f, -283.2f, 283.5f, -283.2f)
                reflectiveCurveToRelative(283.3f, 126.8f, 283.5f, 283.2f)
                horizontalLineToRelative(0f)
                verticalLineToRelative(532.9f)
                horizontalLineToRelative(0f)
                curveToRelative(0f, 156.6f, 126.9f, 283.5f, 283.5f, 283.5f)
                reflectiveCurveToRelative(283.5f, -126.9f, 283.5f, -283.5f)
                verticalLineToRelative(-816.4f)
                curveToRelative(0f, -156.6f, -126.9f, -283.5f, -283.5f, -283.5f)
                horizontalLineToRelative(0f)
                close()
            }
        }.build()

        return _Homebase!!
    }

@Suppress("ObjectPropertyName")
private var _Homebase: ImageVector? = null
