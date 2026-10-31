package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The Filesync logo in one colour: a cloud with the Homebase dot, the desktop app's `menu-bar.svg` cropped to an icon's live area. */
val HomebaseIcons.Filesync: ImageVector
    get() {
        _Filesync?.let { return it }
        return ImageVector.Builder(
            name = "Filesync",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 80f,
            viewportHeight = 80f,
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(18.55f, 45.75f)
                horizontalLineTo(49.9f)
                arcTo(11.55f, 11.55f, 0f, isMoreThanHalf = false, isPositiveArc = true, 49.9f, 68.85f)
                horizontalLineTo(18.55f)
                arcTo(11.55f, 11.55f, 0f, isMoreThanHalf = false, isPositiveArc = true, 18.55f, 45.75f)
                close()
            }
            path(fill = SolidColor(Color.Black)) { circle(21.85f, 47.4f, 11.55f) }
            path(fill = SolidColor(Color.Black)) { circle(38.35f, 40.8f, 16.5f) }
            path(fill = SolidColor(Color.Black)) { circle(51.55f, 50.7f, 9.9f) }
            path(fill = SolidColor(Color.Black)) { circle(63.925f, 20.175f, 9.075f) }
        }.build().also { _Filesync = it }
    }

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, 2 * r, 0f)
    arcToRelative(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, -2 * r, 0f)
    close()
}

@Suppress("ObjectPropertyName")
private var _Filesync: ImageVector? = null
