package id.homebase.core.util

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

// On the host view, not window.addFlags: setKeepScreenOn re-aggregates into the on-screen window's FLAG_KEEP_SCREEN_ON.
@Composable
actual fun KeepScreenOn(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        if (active) view.holdAwake(+1)
        onDispose { if (active) view.holdAwake(-1) }
    }
}

// Main thread only: Compose effects are already serialised there.
private val awakeHolds = WeakHashMap<View, Int>()

private fun View.holdAwake(delta: Int) {
    val holds = (awakeHolds[this] ?: 0) + delta
    if (holds > 0) awakeHolds[this] = holds else awakeHolds.remove(this)
    keepScreenOn = holds > 0
}
