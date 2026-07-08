package id.homebase.chat.widget.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Keeps the screen awake while [active] is true, releasing it when [active] goes
 * false and on dispose (#1025).
 *
 * Sets `keepScreenOn` on the Compose host view ([LocalView]) rather than on the
 * deep PlayerView child, and rather than `window.addFlags`: `View.setKeepScreenOn`
 * calls `recomputeViewAttributes`, which re-aggregates the flag into the on-screen
 * window's `FLAG_KEEP_SCREEN_ON` reliably — the PlayerView path didn't (a
 * SurfaceView-backed player doesn't force the container to redraw each frame) and a
 * direct `window.addFlags` on the resolved Activity window didn't surface on-screen
 * either on the test device.
 *
 * Reference-counted so several surfaces sharing the same host view (e.g. the
 * Moments feed's inline tiles) compose correctly: the flag is raised on 0→1 and
 * cleared on 1→0, so one surface's release can't drop it while another still plays.
 *
 * ponytail: the counter is a plain Int, no synchronisation — Compose effects run on
 * the main thread, so all raise/release calls are already serialised.
 */
@Composable
internal fun KeepScreenOn(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active) {
        if (active && keepScreenOnRefCount++ == 0) view.keepScreenOn = true
        onDispose {
            if (active && --keepScreenOnRefCount == 0) view.keepScreenOn = false
        }
    }
}

private var keepScreenOnRefCount = 0
