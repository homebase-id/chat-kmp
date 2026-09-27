package id.homebase.core.camera

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

@Composable
internal actual fun rememberRawDeviceRotation(): QuarterTurn? {
    val context = LocalContext.current
    var raw by remember { mutableStateOf<QuarterTurn?>(null) }
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                raw = DeviceRotation.quarterTurnFor(orientation, raw)
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }
    return raw
}

@Composable
actual fun rememberDisplayRotation(): QuarterTurn {
    val view = LocalView.current
    var rotation by remember(view) { mutableStateOf(view.displayQuarterTurn()) }
    DisposableEffect(view) {
        val displays = view.context.getSystemService(DisplayManager::class.java)
        // A 180° flip between the two landscapes changes no configuration, so only a display listener sees it.
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                rotation = view.displayQuarterTurn()
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        displays?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displays?.unregisterDisplayListener(listener) }
    }
    return rotation
}

private fun android.view.View.displayQuarterTurn(): QuarterTurn = when (display?.rotation) {
    Surface.ROTATION_90 -> QuarterTurn.R270
    Surface.ROTATION_180 -> QuarterTurn.R180
    Surface.ROTATION_270 -> QuarterTurn.R90
    else -> QuarterTurn.R0
}

/** Clockwise physical rotation → CameraX target rotation (Surface.ROTATION_* is counter-clockwise). */
internal val QuarterTurn.surfaceRotation: Int
    get() = when (this) {
        QuarterTurn.R0 -> Surface.ROTATION_0
        QuarterTurn.R90 -> Surface.ROTATION_270
        QuarterTurn.R180 -> Surface.ROTATION_180
        QuarterTurn.R270 -> Surface.ROTATION_90
    }
