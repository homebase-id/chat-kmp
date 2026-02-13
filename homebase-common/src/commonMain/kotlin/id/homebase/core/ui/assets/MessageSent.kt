package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val HomebaseIcons.MessageSent: ImageVector
    get() {
        if (_MessageSent != null) {
            return _MessageSent!!
        }
        _MessageSent = ImageVector.Builder(
            name = "MessageSent",
            defaultWidth = 20.dp,
            defaultHeight = 20.dp,
            viewportWidth = 20f,
            viewportHeight = 20f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 0f)
                curveTo(11.382f, 0f, 12.682f, 0.263f, 13.9f, 0.788f)
                curveTo(15.118f, 1.313f, 16.177f, 2.026f, 17.075f, 2.925f)
                curveTo(17.974f, 3.824f, 18.686f, 4.882f, 19.213f, 6.1f)
                curveTo(19.74f, 7.317f, 20.002f, 8.617f, 20f, 10f)
                curveTo(19.998f, 11.383f, 19.735f, 12.683f, 19.212f, 13.9f)
                curveTo(18.689f, 15.118f, 17.976f, 16.176f, 17.075f, 17.075f)
                curveTo(16.174f, 17.974f, 15.116f, 18.687f, 13.9f, 19.213f)
                curveTo(12.685f, 19.739f, 11.385f, 20.001f, 10f, 20f)
                curveTo(8.617f, 20f, 7.316f, 19.737f, 6.1f, 19.212f)
                curveTo(4.883f, 18.687f, 3.825f, 17.974f, 2.925f, 17.075f)
                curveTo(2.025f, 16.176f, 1.313f, 15.118f, 0.788f, 13.9f)
                curveTo(0.263f, 12.683f, 0.001f, 11.383f, 0f, 10f)
                curveTo(-0.001f, 8.617f, 0.262f, 7.317f, 0.788f, 6.1f)
                curveTo(1.314f, 4.882f, 2.026f, 3.824f, 2.925f, 2.925f)
                curveTo(3.823f, 2.026f, 4.882f, 1.313f, 6.1f, 0.788f)
                curveTo(7.318f, 0.263f, 8.618f, 0f, 10f, 0f)
                close()
                moveTo(10f, 2f)
                curveTo(7.767f, 2f, 5.875f, 2.775f, 4.325f, 4.325f)
                curveTo(2.775f, 5.875f, 2f, 7.767f, 2f, 10f)
                curveTo(2f, 12.233f, 2.775f, 14.125f, 4.325f, 15.675f)
                curveTo(5.875f, 17.225f, 7.767f, 18f, 10f, 18f)
                curveTo(12.233f, 18f, 14.125f, 17.225f, 15.675f, 15.675f)
                curveTo(17.225f, 14.125f, 18f, 12.233f, 18f, 10f)
                curveTo(18f, 7.767f, 17.225f, 5.875f, 15.675f, 4.325f)
                curveTo(14.125f, 2.775f, 12.233f, 2f, 10f, 2f)
                close()
                moveTo(14.25f, 6.575f)
                curveTo(14.533f, 6.575f, 14.767f, 6.666f, 14.95f, 6.85f)
                curveTo(15.134f, 7.033f, 15.225f, 7.267f, 15.225f, 7.55f)
                curveTo(15.225f, 7.833f, 15.134f, 8.067f, 14.95f, 8.25f)
                lineTo(9.3f, 13.9f)
                curveTo(9.1f, 14.1f, 8.866f, 14.2f, 8.6f, 14.2f)
                curveTo(8.333f, 14.2f, 8.1f, 14.1f, 7.9f, 13.9f)
                lineTo(5.05f, 11.05f)
                curveTo(4.867f, 10.866f, 4.775f, 10.633f, 4.775f, 10.35f)
                curveTo(4.775f, 10.067f, 4.867f, 9.834f, 5.05f, 9.65f)
                curveTo(5.233f, 9.467f, 5.467f, 9.375f, 5.75f, 9.375f)
                curveTo(6.033f, 9.375f, 6.267f, 9.467f, 6.45f, 9.65f)
                lineTo(8.6f, 11.8f)
                lineTo(13.55f, 6.85f)
                curveTo(13.733f, 6.666f, 13.967f, 6.575f, 14.25f, 6.575f)
                close()
            }
        }.build()

        return _MessageSent!!
    }

@Suppress("ObjectPropertyName")
private var _MessageSent: ImageVector? = null
