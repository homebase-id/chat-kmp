package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Material Symbols "sticker_add" (Outlined family, weight 400, FILL 1). The add-a-sticker
 * tray tile's call-to-action glyph; filled so it reads as a strong affordance at small size
 * against the colourful saved-sticker thumbnails beside it.
 */
public val HomebaseIcons.StickerAdd: ImageVector
    get() {
        if (_stickerAdd != null) {
            return _stickerAdd!!
        }
        _stickerAdd =
            ImageVector.Builder(
                name = "StickerAdd",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
                autoMirror = true,
            )
                .apply {
                    path(
                        fill = SolidColor(Color.Black),
                        fillAlpha = 1f,
                        stroke = null,
                        strokeAlpha = 1f,
                        strokeLineWidth = 1f,
                        strokeLineCap = StrokeCap.Butt,
                        strokeLineJoin = StrokeJoin.Bevel,
                        strokeLineMiter = 1f,
                        pathFillType = PathFillType.NonZero,
                    ) {
                        moveTo(19f, 7f)
                        verticalLineTo(5f)
                        horizontalLineTo(17f)
                        verticalLineTo(3f)
                        horizontalLineToRelative(2f)
                        verticalLineTo(1f)
                        horizontalLineToRelative(2f)
                        verticalLineTo(3f)
                        horizontalLineToRelative(2f)
                        verticalLineTo(5f)
                        horizontalLineTo(21f)
                        verticalLineTo(7f)
                        horizontalLineTo(19f)
                        close()
                        moveTo(13.35f, 9.5f)
                        lineTo(16f, 8.75f)
                        quadTo(16.13f, 8.05f, 15.66f, 7.52f)
                        reflectiveQuadTo(14.5f, 7f)
                        quadTo(13.88f, 7f, 13.44f, 7.44f)
                        reflectiveQuadTo(13f, 8.5f)
                        quadToRelative(0f, 0.27f, 0.1f, 0.52f)
                        reflectiveQuadTo(13.35f, 9.5f)
                        close()
                        moveToRelative(-6f, 1.75f)
                        lineTo(10f, 10.5f)
                        quadTo(10.1f, 9.8f, 9.65f, 9.27f)
                        reflectiveQuadTo(8.5f, 8.75f)
                        quadToRelative(-0.63f, 0f, -1.06f, 0.44f)
                        reflectiveQuadTo(7f, 10.25f)
                        quadToRelative(0f, 0.27f, 0.1f, 0.52f)
                        reflectiveQuadToRelative(0.25f, 0.48f)
                        close()
                        moveTo(11.5f, 15f)
                        quadToRelative(1.73f, 0f, 3f, -1.13f)
                        quadTo(15.78f, 12.75f, 16f, 11.05f)
                        lineTo(8f, 13.3f)
                        quadToRelative(0.65f, 0.8f, 1.55f, 1.25f)
                        reflectiveQuadTo(11.5f, 15f)
                        close()
                        moveTo(15f, 19f)
                        lineToRelative(4f, -4f)
                        horizontalLineTo(17f)
                        quadToRelative(-0.82f, 0f, -1.41f, 0.59f)
                        reflectiveQuadTo(15f, 17f)
                        verticalLineToRelative(2f)
                        close()
                        moveToRelative(1f, 2f)
                        horizontalLineTo(5f)
                        quadTo(4.18f, 21f, 3.59f, 20.41f)
                        reflectiveQuadTo(3f, 19f)
                        verticalLineTo(5f)
                        quadTo(3f, 4.17f, 3.59f, 3.59f)
                        reflectiveQuadTo(5f, 3f)
                        horizontalLineTo(15.1f)
                        quadTo(15.05f, 3.25f, 15.03f, 3.49f)
                        reflectiveQuadTo(15f, 4f)
                        quadToRelative(0f, 2.07f, 1.46f, 3.54f)
                        reflectiveQuadTo(20f, 9f)
                        quadToRelative(0.28f, 0f, 0.51f, -0.03f)
                        reflectiveQuadTo(21f, 8.9f)
                        verticalLineTo(16f)
                        lineToRelative(-5f, 5f)
                        close()
                    }
                }
                .build()
        return _stickerAdd!!
    }

private var _stickerAdd: ImageVector? = null
