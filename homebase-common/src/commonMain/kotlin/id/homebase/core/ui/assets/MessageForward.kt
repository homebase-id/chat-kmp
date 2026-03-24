package id.homebase.core.ui.assets

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val HomebaseIcons.MessageForward: ImageVector
    get() {
        if (_forward != null) {
            return _forward!!
        }
        _forward = materialIcon(name = "MessageForward", autoMirror = true) {
            materialPath {
                moveTo(14.0f, 9.0f)  // 24 - 10 = 14
                verticalLineTo(5.0f)
                lineToRelative(7.0f, 7.0f)  // positive instead of negative
                lineToRelative(-7.0f, 7.0f)  // negative instead of positive
                verticalLineToRelative(-4.1f)
                curveToRelative(-5.0f, 0.0f, -8.5f, 1.6f, -11.0f, 5.1f)  // negate x-related values
                curveToRelative(1.0f, -5.0f, 4.0f, -10.0f, 11.0f, -11.0f)  // adjust curve
                close()
            }
        }
        return _forward!!
    }

private var _forward: ImageVector? = null
