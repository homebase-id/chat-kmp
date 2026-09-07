package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The bare 'h' — same glyph paths as [Homebase] with the tile dropped and the viewport cropped to
 * the glyph, so `Icon`'s tint is the only thing that colours it.
 *
 * **A deliberate deviation, not a manual variant.** The manual shows no bare-glyph mark: p9's
 * negative version is a solid black rounded square holding a white 'h', and its third variant is a
 * white square with a black outline. Both assume a light or neutral ground. The login brand pane is
 * a purple-to-blue night sky, where the positive tile's own gradient collides with the ground and a
 * black tile punches a hole in it, so the glyph is used alone. The cost is p7: its clear-space X is
 * defined from the tile edge, so with no tile the margin is measured from the glyph box instead —
 * more generous than the rule, never less. Restoring a tile is the alternative if the ground changes.
 */
val HomebaseIcons.HomebaseMark: ImageVector
    get() {
        if (_HomebaseMark != null) {
            return _HomebaseMark!!
        }
        _HomebaseMark = ImageVector.Builder(
            name = "HomebaseMark",
            defaultWidth = 2199.8.dp,
            defaultHeight = 2199.8.dp,
            viewportWidth = 2199.8f,
            viewportHeight = 2199.8f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(1666.9f, 283.5f)
                moveToRelative(-283.5f, 0f)
                arcToRelative(283.5f, 283.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 567f, 0f)
                arcToRelative(283.5f, 283.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, -567f, 0f)
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(1666.9f, 816.4f)
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

        return _HomebaseMark!!
    }

@Suppress("ObjectPropertyName")
private var _HomebaseMark: ImageVector? = null
