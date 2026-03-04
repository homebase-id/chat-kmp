package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val HomebaseIcons.Apk: ImageVector
    get() {
        if (_Apk != null) {
            return _Apk!!
        }
        _Apk =
            ImageVector.Builder(
                name = "Apk",
                defaultWidth = 576.dp,
                defaultHeight = 512.dp,
                viewportWidth = 576f,
                viewportHeight = 512f
            )
                .apply {
                    path(fill = SolidColor(Color.Black)) {
                        moveTo(420.6f, 301.9f)
                        arcToRelative(
                            24f,
                            24f,
                            0f,
                            isMoreThanHalf = true,
                            isPositiveArc = true,
                            24f,
                            -24f
                        )
                        arcToRelative(
                            24f,
                            24f,
                            0f,
                            isMoreThanHalf = false,
                            isPositiveArc = true,
                            -24f,
                            24f
                        )
                        close()

                        moveToRelative(-265.1f, 0f)
                        arcToRelative(
                            24f,
                            24f,
                            0f,
                            isMoreThanHalf = true,
                            isPositiveArc = true,
                            24f,
                            -24f
                        )
                        arcToRelative(
                            24f,
                            24f,
                            0f,
                            isMoreThanHalf = false,
                            isPositiveArc = true,
                            -24f,
                            24f
                        )
                        close()

                        moveToRelative(273.7f, -144.5f)
                        lineToRelative(47.9f, -83f)
                        arcToRelative(
                            10f,
                            10f,
                            0f,
                            isMoreThanHalf = true,
                            isPositiveArc = false,
                            -17.3f,
                            -10f
                        )
                        horizontalLineToRelative(0f)
                        lineToRelative(-48.5f, 84.1f)
                        arcToRelative(
                            301.3f,
                            301.3f,
                            0f,
                            isMoreThanHalf = false,
                            isPositiveArc = false,
                            -246.6f,
                            0f
                        )
                        lineToRelative(-48.5f, -84.1f)
                        arcToRelative(
                            10f,
                            10f,
                            0f,
                            isMoreThanHalf = true,
                            isPositiveArc = false,
                            -17.3f,
                            10f
                        )
                        horizontalLineToRelative(0f)
                        lineToRelative(47.9f, 83f)
                        curveTo(64.5f, 202.2f, 8.2f, 285.6f, 0f, 384f)
                        horizontalLineToRelative(576f)
                        curveToRelative(-8.2f, -98.5f, -64.5f, -181.8f, -146.9f, -226.6f)
                        close()
                    }
                }
                .build()

        return _Apk!!
    }

@Suppress("ObjectPropertyName")
private var _Apk: ImageVector? = null
