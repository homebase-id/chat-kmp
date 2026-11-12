package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/**
 * Draws the live viewfinder of [engine] and reports a long press at the touched offset through [onLongPressFocus].
 * Draws an empty box when [engine] is not this platform's own engine. On desktop and web, which have no camera
 * capture, it draws nothing and never calls [onLongPressFocus].
 */
@Composable
expect fun CameraPreview(
    engine: CameraEngine,
    modifier: Modifier = Modifier,
    onLongPressFocus: (Offset) -> Unit = {},
)
