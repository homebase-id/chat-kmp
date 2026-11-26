package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Marketing's Photos/svg/photos-glyph(-filled).svg: mountains under the Homebase dot, as on the Drive cloud.
private const val Mountains = "M2.8 19.5L9.2 10.8L12.9 15.2L16.4 11.4L21.2 19.5Z"

private fun photos(name: String, filled: Boolean, dot: String): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        addPath(
            pathData = addPathNodes(Mountains + dot),
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

/** The Photos logo in one colour: the bar's selected state. */
val HomebaseIcons.Photos: ImageVector
    get() = _Photos ?: photos("Photos", filled = true, dot = "M15 5.4a2.6 2.6 0 1 0 5.2 0a2.6 2.6 0 1 0 -5.2 0Z").also { _Photos = it }

/** [Photos] as outlines, the dot a little smaller so it weighs the same: the bar's unselected state. */
val HomebaseIcons.PhotosOutlined: ImageVector
    get() = _PhotosOutlined ?: photos("PhotosOutlined", filled = false, dot = "M15.3 5.4a2.3 2.3 0 1 0 4.6 0a2.3 2.3 0 1 0 -4.6 0Z").also { _PhotosOutlined = it }

@Suppress("ObjectPropertyName")
private var _Photos: ImageVector? = null

@Suppress("ObjectPropertyName")
private var _PhotosOutlined: ImageVector? = null
