package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * True while the user has asked the OS to cut animation: Android animator scale 0, iOS Reduce Motion.
 * Re-read when the app resumes (Android) or the setting changes (iOS). Always false on desktop and web, which have no such signal here.
 */
@Composable
internal expect fun rememberReduceMotion(): Boolean

/** The camera's hardware capture button (iOS camera-control and volume buttons); Android uses key events instead. */
@Composable
internal expect fun HardwareShutterEffect(onDown: () -> Unit, onUp: () -> Unit)
