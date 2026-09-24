package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

@Composable
expect fun CameraPreview(
    engine: CameraEngine,
    modifier: Modifier = Modifier,
    onLongPressFocus: (Offset) -> Unit = {},
)
