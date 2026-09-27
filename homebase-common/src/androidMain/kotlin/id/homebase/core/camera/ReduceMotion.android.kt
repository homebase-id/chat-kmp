package id.homebase.core.camera

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

@Composable
internal actual fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    var reduce by remember(context) { mutableStateOf(context.animationsOff()) }
    LifecycleResumeEffect(context) {
        reduce = context.animationsOff()
        onPauseOrDispose { }
    }
    return reduce
}

private fun Context.animationsOff(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
internal actual fun HardwareShutterEffect(onDown: () -> Unit, onUp: () -> Unit) = Unit
