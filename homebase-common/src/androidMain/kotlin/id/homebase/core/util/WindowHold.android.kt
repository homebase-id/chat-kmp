package id.homebase.core.util

import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.util.WeakHashMap

/**
 * A window setting several composables can hold at once: the first hold applies it and keeps what [apply] returns
 * (the state before), and the last release hands that to [restore].
 */
internal class WindowHolds<T>(private val apply: Window.() -> T, private val restore: Window.(T) -> Unit) {
    private class Hold<T>(val before: T) {
        var holders = 0
    }

    // Main thread only: Compose effects are already serialised there.
    private val holds = WeakHashMap<Window, Hold<T>>()

    fun hold(window: Window) {
        holds.getOrPut(window) { Hold(window.apply()) }.holders++
    }

    fun release(window: Window) {
        val hold = holds[window] ?: return
        if (--hold.holders == 0) {
            holds.remove(window)
            window.restore(hold.before)
        }
    }
}

/** Holds [holds] on the window this is composed in (a dialog's own, else the activity's) while [active]. */
@Composable
internal fun <T> WindowHoldEffect(holds: WindowHolds<T>, active: Boolean) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: LocalActivity.current?.window
    DisposableEffect(window, active) {
        if (window != null && active) holds.hold(window)
        onDispose { if (window != null && active) holds.release(window) }
    }
}
