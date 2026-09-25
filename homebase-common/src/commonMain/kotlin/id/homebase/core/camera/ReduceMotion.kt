package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
internal expect fun rememberReduceMotion(): Boolean

/** The camera's hardware capture button (iOS camera-control and volume buttons); Android uses key events instead. */
@Composable
internal expect fun HardwareShutterEffect(onDown: () -> Unit, onUp: () -> Unit)
