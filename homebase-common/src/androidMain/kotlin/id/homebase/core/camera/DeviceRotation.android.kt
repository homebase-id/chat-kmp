package id.homebase.core.camera

import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

@Composable
actual fun rememberDeviceRotation(): QuarterTurn {
    val context = LocalContext.current
    var raw by remember { mutableStateOf<QuarterTurn?>(null) }
    var committed by remember { mutableStateOf<QuarterTurn?>(null) }

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

    LaunchedEffect(raw) {
        val next = raw ?: return@LaunchedEffect
        if (committed != null) delay(DeviceRotation.SETTLE_MS)
        committed = next
    }

    return committed ?: QuarterTurn.R0
}

/** Clockwise physical rotation → CameraX target rotation (Surface.ROTATION_* is counter-clockwise). */
internal val QuarterTurn.surfaceRotation: Int
    get() = when (this) {
        QuarterTurn.R0 -> Surface.ROTATION_0
        QuarterTurn.R90 -> Surface.ROTATION_270
        QuarterTurn.R180 -> Surface.ROTATION_180
        QuarterTurn.R270 -> Surface.ROTATION_90
    }
