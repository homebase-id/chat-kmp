package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

// The owner console's Grid path, 288x320, centred so it keeps its proportions on a square icon.
private const val GridPath = "M128 40C128 17.9 110.1 0 88 0H40C17.9 0 0 17.9 0 40V88C0 110.1 17.9 128 40 128H88C110.1 128 128 110.1 128 88V40ZM128 232C128 209.9 110.1 192 88 192H40C17.9 192 0 209.9 0 232V280C0 302.1 17.9 320 40 320H88C110.1 320 128 302.1 128 280V232ZM160 40V88C160 110.1 177.9 128 200 128H248C270.1 128 288 110.1 288 88V40C288 17.9 270.1 0 248 0H200C177.9 0 160 17.9 160 40ZM288 232C288 209.9 270.1 192 248 192H200C177.9 192 160 209.9 160 232V280C160 302.1 177.9 320 200 320H248C270.1 320 288 302.1 288 280V232Z"

val HomebaseIcons.Apps: ImageVector
    get() {
        _Apps?.let { return it }
        return ImageVector.Builder(
            name = "Apps",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 376f,
            viewportHeight = 376f,
        ).apply {
            group(translationX = 44f, translationY = 28f) {
                addPath(pathData = addPathNodes(GridPath), fill = SolidColor(Color.Black))
            }
        }.build().also { _Apps = it }
    }

@Suppress("ObjectPropertyName")
private var _Apps: ImageVector? = null
