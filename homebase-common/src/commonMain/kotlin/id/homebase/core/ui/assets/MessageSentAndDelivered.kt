package id.homebase.core.ui.assets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val HomebaseIcons.MessageSentAndDelivered: ImageVector
    get() {
        if (_MessageSentAndDelivered != null) {
            return _MessageSentAndDelivered!!
        }
        _MessageSentAndDelivered = ImageVector.Builder(
            name = "MessageSentAndDelivered",
            defaultWidth = 29.dp,
            defaultHeight = 20.dp,
            viewportWidth = 29f,
            viewportHeight = 20f
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.EvenOdd
            ) {
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
                moveTo(19f, 2f)
                curveTo(16.767f, 2f, 14.875f, 2.775f, 13.325f, 4.325f)
                curveTo(11.775f, 5.875f, 11f, 7.767f, 11f, 10f)
                curveTo(11f, 12.233f, 11.775f, 14.125f, 13.325f, 15.675f)
                curveTo(14.875f, 17.225f, 16.767f, 18f, 19f, 18f)
                curveTo(21.233f, 18f, 23.125f, 17.225f, 24.675f, 15.675f)
                curveTo(26.225f, 14.125f, 27f, 12.233f, 27f, 10f)
                curveTo(27f, 7.767f, 26.225f, 5.875f, 24.675f, 4.325f)
                curveTo(23.125f, 2.775f, 21.233f, 2f, 19f, 2f)
                close()
                moveTo(23.25f, 6.575f)
                curveTo(23.533f, 6.575f, 23.767f, 6.666f, 23.95f, 6.85f)
                curveTo(24.133f, 7.033f, 24.225f, 7.267f, 24.225f, 7.55f)
                curveTo(24.225f, 7.833f, 24.133f, 8.067f, 23.95f, 8.25f)
                lineTo(18.3f, 13.9f)
                curveTo(18.1f, 14.1f, 17.866f, 14.2f, 17.6f, 14.2f)
                curveTo(17.333f, 14.2f, 17.1f, 14.1f, 16.9f, 13.9f)
                lineTo(14.05f, 11.05f)
                curveTo(13.867f, 10.866f, 13.775f, 10.633f, 13.775f, 10.35f)
                curveTo(13.776f, 10.067f, 13.867f, 9.834f, 14.05f, 9.65f)
                curveTo(14.233f, 9.467f, 14.467f, 9.375f, 14.75f, 9.375f)
                curveTo(15.033f, 9.375f, 15.267f, 9.467f, 15.45f, 9.65f)
                lineTo(17.6f, 11.8f)
                lineTo(22.55f, 6.85f)
                curveTo(22.733f, 6.666f, 22.967f, 6.575f, 23.25f, 6.575f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 0f)
                curveTo(11.275f, 0f, 12.48f, 0.227f, 13.615f, 0.674f)
                curveTo(12.839f, 1.077f, 12.132f, 1.56f, 11.495f, 2.126f)
                curveTo(11.015f, 2.043f, 10.517f, 2f, 10f, 2f)
                curveTo(7.767f, 2f, 5.875f, 2.775f, 4.325f, 4.325f)
                curveTo(2.775f, 5.875f, 2f, 7.767f, 2f, 10f)
                curveTo(2f, 12.233f, 2.775f, 14.125f, 4.325f, 15.675f)
                curveTo(5.875f, 17.225f, 7.767f, 18f, 10f, 18f)
                curveTo(10.575f, 18f, 11.127f, 17.946f, 11.656f, 17.844f)
                curveTo(12.303f, 18.394f, 13.016f, 18.864f, 13.799f, 19.253f)
                curveTo(12.612f, 19.75f, 11.347f, 20.001f, 10f, 20f)
                curveTo(8.617f, 20f, 7.316f, 19.737f, 6.1f, 19.212f)
                curveTo(4.883f, 18.687f, 3.825f, 17.974f, 2.925f, 17.075f)
                curveTo(2.025f, 16.176f, 1.313f, 15.118f, 0.788f, 13.9f)
                curveTo(0.263f, 12.683f, 0.001f, 11.383f, 0f, 10f)
                curveTo(-0.001f, 8.617f, 0.262f, 7.317f, 0.788f, 6.1f)
                curveTo(1.314f, 4.882f, 2.026f, 3.824f, 2.925f, 2.925f)
                curveTo(3.823f, 2.026f, 4.882f, 1.313f, 6.1f, 0.788f)
                curveTo(7.318f, 0.263f, 8.618f, 0f, 10f, 0f)
                close()
                moveTo(5.75f, 9.375f)
                curveTo(6.033f, 9.375f, 6.267f, 9.467f, 6.45f, 9.65f)
                lineTo(8.09f, 11.29f)
                curveTo(8.21f, 12.226f, 8.451f, 13.123f, 8.82f, 13.979f)
                curveTo(8.845f, 14.038f, 8.873f, 14.095f, 8.899f, 14.152f)
                curveTo(8.805f, 14.183f, 8.705f, 14.2f, 8.6f, 14.2f)
                curveTo(8.333f, 14.2f, 8.1f, 14.1f, 7.9f, 13.9f)
                lineTo(5.05f, 11.05f)
                curveTo(4.867f, 10.866f, 4.775f, 10.633f, 4.775f, 10.35f)
                curveTo(4.775f, 10.067f, 4.867f, 9.834f, 5.05f, 9.65f)
                curveTo(5.233f, 9.467f, 5.467f, 9.375f, 5.75f, 9.375f)
                close()
            }
        }.build()

        return _MessageSentAndDelivered!!
    }

@Suppress("ObjectPropertyName")
private var _MessageSentAndDelivered: ImageVector? = null
