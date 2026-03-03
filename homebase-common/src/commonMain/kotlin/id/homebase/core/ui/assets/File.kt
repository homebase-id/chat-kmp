package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val HomebaseIcons.File: ImageVector
    get() {
        if (_File != null) {
            return _File!!
        }
        _File =
            ImageVector.Builder(
                name = "File",
                defaultWidth = 384.dp,
                defaultHeight = 512.dp,
                viewportWidth = 384f,
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
                        verticalLineToRelative(288f)
                        curveToRelative(0f, 35.3f, -28.7f, 64f, -64f, 64f)
                        lineTo(64f, 512f)
                        curveToRelative(-35.3f, 0f, -64f, -28.7f, -64f, -64f)
                        lineTo(0f, 64f)
                        close()

                        moveTo(384f, 128f)
                        horizontalLineToRelative(-128f)
                        lineTo(256f, 0f)
                        lineTo(384f, 128f)
                        close()
                    }
                }
                .build()

        return _File!!
    }

@Suppress("ObjectPropertyName")
private var _File: ImageVector? = null
