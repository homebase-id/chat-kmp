package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val HomebaseIcons.Warning: ImageVector
    get() {
        if (_Warning != null) {
            return _Warning!!
        }
        _Warning =
                ImageVector.Builder(
                                name = "Warning",
                                defaultWidth = 24.0.dp,
                                defaultHeight = 24.0.dp,
                                viewportWidth = 24.0f,
                                viewportHeight = 24.0f
                        )
                        .apply {
                            path(
                                    fill = SolidColor(Color.Black),
                                    fillAlpha = 1f,
                                    stroke = null,
                                    strokeAlpha = 1f,
                                    strokeLineWidth = 1.0f,
                                    strokeLineCap = StrokeCap.Butt,
                                    strokeLineJoin = StrokeJoin.Miter,
                                    strokeLineMiter = 1.0f,
                                    pathFillType = PathFillType.NonZero
                            ) {
                                moveTo(1.0f, 21.0f)
                                horizontalLineToRelative(22.0f)
                                lineTo(12.0f, 2.0f)
                                lineTo(1.0f, 21.0f)
                                close()
                                moveTo(13.0f, 18.0f)
                                horizontalLineToRelative(-2.0f)
                                verticalLineToRelative(-2.0f)
                                horizontalLineToRelative(2.0f)
                                verticalLineToRelative(2.0f)
                                close()
                                moveTo(13.0f, 14.0f)
                                horizontalLineToRelative(-2.0f)
                                verticalLineToRelative(-4.0f)
                                horizontalLineToRelative(2.0f)
                                verticalLineToRelative(4.0f)
                                close()
                            }
                        }
                        .build()
        return _Warning!!
    }

private var _Warning: ImageVector? = null
