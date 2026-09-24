package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

@Composable
actual fun rememberCameraEngine(): CameraEngine = remember { UnavailableCameraEngine() }

@Composable
actual fun CameraPreview(engine: CameraEngine, modifier: Modifier, onTapFocus: (Offset) -> Unit) = Unit

@Composable
internal actual fun rememberRawDeviceRotation(): QuarterTurn? = null
