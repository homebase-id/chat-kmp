package id.homebase.core.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

/**
 * The Compose overlays (dialogs, sheets, menus, tooltips, snackbars) open on a screen that provides them through
 * [LocalOpenOverlays], each with the window area it covers. A screen hosting a native view that paints over
 * Compose asks [covers] to step that view aside while an overlay is over it.
 */
@Stable
class OpenOverlays {
    private val areas = mutableStateMapOf<Any, Rect>()

    fun covers(area: Rect): Boolean = areas.values.any { it.overlaps(area) }

    internal fun put(key: Any, area: Rect) {
        areas[key] = area
    }

    internal fun remove(key: Any) {
        areas.remove(key)
    }
}

val LocalOpenOverlays = staticCompositionLocalOf<OpenOverlays?> { null }

private val Everywhere = Rect(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)

/**
 * Reports the caller as an open overlay over [area] (window coordinates; the whole window by default) for as long
 * as it is composed. A null area reports nothing yet. A no-op where no screen provides [LocalOpenOverlays].
 */
@Composable
fun ReportOverlay(area: Rect? = Everywhere) {
    val overlays = LocalOpenOverlays.current ?: return
    val key = remember { Any() }
    DisposableEffect(overlays, area) {
        if (area != null) overlays.put(key, area)
        onDispose { overlays.remove(key) }
    }
}

@Composable
fun ProvideOpenOverlays(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOpenOverlays provides remember { OpenOverlays() }, content = content)
}
