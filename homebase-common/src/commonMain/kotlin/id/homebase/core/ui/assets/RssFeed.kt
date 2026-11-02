package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

private const val RssPath = "M6.18 15.64a2.18 2.18 0 1 1 0 4.36a2.18 2.18 0 1 1 0-4.36zM4 4.44v2.83c7.03 0 12.73 5.7 12.73 12.73h2.83c0-8.59-6.97-15.56-15.56-15.56zM4 10.1v2.83c3.9 0 7.07 3.17 7.07 7.07h2.83c0-5.47-4.43-9.9-9.9-9.9z"

/** Material's RSS glyph cropped to fill the same live area as the other tab icons. */
val HomebaseIcons.RssFeed: ImageVector
    get() {
        _RssFeed?.let { return it }
        return ImageVector.Builder(
            name = "RssFeed",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 20f,
            viewportHeight = 20f,
        ).apply {
            group(translationX = -1.78f, translationY = -2.22f) {
                addPath(pathData = addPathNodes(RssPath), fill = SolidColor(Color.Black))
            }
        }.build().also { _RssFeed = it }
    }

@Suppress("ObjectPropertyName")
private var _RssFeed: ImageVector? = null
