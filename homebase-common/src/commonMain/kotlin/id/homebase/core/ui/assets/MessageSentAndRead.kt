package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val HomebaseIcons.MessageSentAndRead: ImageVector
    get() {
        if (_MessageSentAndRead != null) {
            return _MessageSentAndRead!!
        }
        _MessageSentAndRead = ImageVector.Builder(
            name = "MessageSentAndRead",
            defaultWidth = 29.dp,
            defaultHeight = 20.dp,
            viewportWidth = 29f,
            viewportHeight = 20f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 0f)
                curveTo(11.318f, 0f, 12.561f, 0.242f, 13.729f, 0.72f)
                curveTo(12.738f, 1.226f, 11.857f, 1.861f, 11.089f, 2.63f)
                curveTo(10.153f, 3.567f, 9.41f, 4.669f, 8.862f, 5.938f)
                curveTo(8.314f, 7.206f, 8.041f, 8.56f, 8.042f, 10f)
                curveTo(8.042f, 10.451f, 8.072f, 10.892f, 8.126f, 11.326f)
                lineTo(6.45f, 9.65f)
                curveTo(6.267f, 9.467f, 6.033f, 9.375f, 5.75f, 9.375f)
                curveTo(5.467f, 9.375f, 5.233f, 9.467f, 5.05f, 9.65f)
                curveTo(4.867f, 9.834f, 4.775f, 10.067f, 4.775f, 10.35f)
                curveTo(4.775f, 10.633f, 4.867f, 10.866f, 5.05f, 11.05f)
                lineTo(7.9f, 13.9f)
                curveTo(8.1f, 14.1f, 8.333f, 14.2f, 8.6f, 14.2f)
                curveTo(8.706f, 14.2f, 8.807f, 14.183f, 8.902f, 14.151f)
                curveTo(9.445f, 15.382f, 10.174f, 16.455f, 11.089f, 17.37f)
                curveTo(11.858f, 18.139f, 12.74f, 18.774f, 13.731f, 19.28f)
                curveTo(12.564f, 19.759f, 11.321f, 20.001f, 10f, 20f)
                curveTo(8.617f, 20f, 7.316f, 19.737f, 6.1f, 19.212f)
                curveTo(4.883f, 18.687f, 3.825f, 17.974f, 2.925f, 17.075f)
                curveTo(2.025f, 16.176f, 1.313f, 15.118f, 0.788f, 13.9f)
                curveTo(0.263f, 12.683f, 0.001f, 11.383f, 0f, 10f)
                curveTo(-0.001f, 8.617f, 0.262f, 7.317f, 0.788f, 6.1f)
                curveTo(1.314f, 4.882f, 2.026f, 3.824f, 2.925f, 2.925f)
                curveTo(3.823f, 2.026f, 4.882f, 1.313f, 6.1f, 0.788f)
                curveTo(7.318f, 0.263f, 8.618f, 0f, 10f, 0f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 0f)
                curveTo(20.382f, 0f, 21.682f, 0.263f, 22.9f, 0.788f)
                curveTo(24.118f, 1.313f, 25.177f, 2.026f, 26.075f, 2.925f)
                curveTo(26.974f, 3.824f, 27.686f, 4.882f, 28.213f, 6.1f)
                curveTo(28.74f, 7.317f, 29.002f, 8.617f, 29f, 10f)
                curveTo(28.998f, 11.383f, 28.735f, 12.683f, 28.212f, 13.9f)
                curveTo(27.689f, 15.118f, 26.976f, 16.176f, 26.075f, 17.075f)
                curveTo(25.174f, 17.974f, 24.116f, 18.687f, 22.9f, 19.213f)
                curveTo(21.685f, 19.739f, 20.385f, 20.001f, 19f, 20f)
                curveTo(17.617f, 20f, 16.316f, 19.737f, 15.1f, 19.212f)
                curveTo(13.883f, 18.687f, 12.825f, 17.974f, 11.925f, 17.075f)
                curveTo(11.025f, 16.176f, 10.313f, 15.118f, 9.788f, 13.9f)
                curveTo(9.263f, 12.683f, 9.001f, 11.383f, 9f, 10f)
                curveTo(8.999f, 8.617f, 9.262f, 7.317f, 9.788f, 6.1f)
                curveTo(10.314f, 4.882f, 11.026f, 3.824f, 11.925f, 2.925f)
                curveTo(12.823f, 2.026f, 13.882f, 1.313f, 15.1f, 0.788f)
                curveTo(16.318f, 0.263f, 17.618f, 0f, 19f, 0f)
                close()
                moveTo(23.25f, 6.575f)
                curveTo(22.967f, 6.575f, 22.733f, 6.666f, 22.55f, 6.85f)
                lineTo(17.6f, 11.8f)
                lineTo(15.45f, 9.65f)
                curveTo(15.267f, 9.467f, 15.033f, 9.375f, 14.75f, 9.375f)
                curveTo(14.467f, 9.375f, 14.233f, 9.467f, 14.05f, 9.65f)
                curveTo(13.867f, 9.834f, 13.776f, 10.067f, 13.775f, 10.35f)
                curveTo(13.775f, 10.633f, 13.867f, 10.866f, 14.05f, 11.05f)
                lineTo(16.9f, 13.9f)
                curveTo(17.1f, 14.1f, 17.333f, 14.2f, 17.6f, 14.2f)
                curveTo(17.866f, 14.2f, 18.1f, 14.1f, 18.3f, 13.9f)
                lineTo(23.95f, 8.25f)
                curveTo(24.133f, 8.067f, 24.225f, 7.833f, 24.225f, 7.55f)
                curveTo(24.225f, 7.267f, 24.133f, 7.033f, 23.95f, 6.85f)
                curveTo(23.767f, 6.666f, 23.533f, 6.575f, 23.25f, 6.575f)
                close()
            }
        }.build()

        return _MessageSentAndRead!!
    }

@Suppress("ObjectPropertyName")
private var _MessageSentAndRead: ImageVector? = null
