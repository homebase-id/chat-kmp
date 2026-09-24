package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

@Composable
actual fun rememberCameraEngine(recordsVideo: Boolean, warm: CameraEngine?): CameraEngine = remember { UnavailableCameraEngine() }

@Composable
actual fun rememberCameraWarmer(): CameraWarmer = remember { CameraWarmer { null } }

@Composable
actual fun CameraPreview(
    engine: CameraEngine,
    modifier: Modifier,
    onTapFocus: (Offset) -> Unit,
    onLongPressFocus: (Offset) -> Unit,
) = Unit

@Composable
internal actual fun rememberRawDeviceRotation(): QuarterTurn? = null

@Composable
actual fun rememberDisplayRotation(): QuarterTurn = QuarterTurn.R0

@Composable
internal actual fun rememberReduceMotion(): Boolean = false

@Composable
internal actual fun HardwareShutterEffect(onDown: () -> Unit, onUp: () -> Unit) = Unit
