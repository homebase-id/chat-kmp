package id.homebase.core.image

import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import coil3.BitmapImage
import coil3.Image
import java.util.WeakHashMap

internal actual fun carriesGainmap(image: Image): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && (image as? BitmapImage)?.bitmap?.hasGainmap() == true

@Composable
internal actual fun HdrWindowEffect(active: Boolean) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: LocalActivity.current?.window
    DisposableEffect(window, active) {
        if (window != null && active) window.holdHdr()
        onDispose { if (window != null && active) window.releaseHdr() }
    }
}

private class HdrHold(val modeBefore: Int) {
    var holders = 0
}

// Main thread only: Compose effects are already serialised there. A pager keeps neighbouring pages composed, so
// two HDR photos can hold the window at once.
private val hdrHolds = WeakHashMap<Window, HdrHold>()

private fun Window.holdHdr() {
    val hold = hdrHolds.getOrPut(this) { HdrHold(colorMode) }
    if (hold.holders++ == 0) colorMode = ActivityInfo.COLOR_MODE_HDR
}

private fun Window.releaseHdr() {
    val hold = hdrHolds[this] ?: return
    if (--hold.holders == 0) {
        colorMode = hold.modeBefore
        hdrHolds.remove(this)
    }
}
