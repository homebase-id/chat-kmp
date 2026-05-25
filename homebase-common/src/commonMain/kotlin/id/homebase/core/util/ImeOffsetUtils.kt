package id.homebase.core.util

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset

/**
 * Returns the pure keyboard height in pixels, excluding the navigation bar
 * (home indicator) inset that [WindowInsets.ime] includes on iOS.
 *
 * On iOS, [WindowInsets.ime] reports keyboard height + safe area bottom.
 * Using the raw value as an offset overshoots by the safe area, leaving a
 * gap between the content and the keyboard. Subtracting [WindowInsets.navigationBars]
 * gives the keyboard-only height.
 */
fun pureImeBottomPx(
    imeInsets: WindowInsets,
    navBarInsets: WindowInsets,
    density: Density,
): Int {
    val ime = imeInsets.getBottom(density)
    val nav = navBarInsets.getBottom(density)
    return (ime - nav).coerceAtLeast(0)
}

/**
 * Whether the IME (software keyboard) is currently visible.
 */
fun isImeVisible(imeInsets: WindowInsets, density: Density): Boolean =
    imeInsets.getBottom(density) > 0

/**
 * Remembers the [WindowInsets.ime] and [WindowInsets.navigationBars] so they
 * can be read inside non-composable lambdas (e.g. [Modifier.offset]).
 */
data class ImeOffsetState(
    val imeInsets: WindowInsets,
    val navBarInsets: WindowInsets,
    val density: Density,
) {
    val pureImeBottomPx: Int get() = pureImeBottomPx(imeInsets, navBarInsets, density)
    val imeBottomPx: Int get() = imeInsets.getBottom(density)
    val isImeVisible: Boolean get() = imeBottomPx > 0
}

@Composable
fun rememberImeOffsetState(): ImeOffsetState {
    val imeInsets = WindowInsets.ime
    val navBarInsets = WindowInsets.navigationBars
    val density = LocalDensity.current
    return remember(imeInsets, navBarInsets, density) {
        ImeOffsetState(imeInsets, navBarInsets, density)
    }
}
