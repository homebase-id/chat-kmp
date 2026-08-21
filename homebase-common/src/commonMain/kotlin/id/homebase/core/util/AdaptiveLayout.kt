package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass

// Reads the raw window width rather than the size class, because
// WindowSizeClass.isWidthAtLeastBreakpoint compares against the bucket bound
// (0/600/840) and so cannot express a threshold above 840.
@Composable
fun isExpandedLayout(
    minWidthDp: Int = WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
): Boolean {
    val widthPx = LocalWindowInfo.current.containerSize.width
    return widthPx > 0 && with(LocalDensity.current) { widthPx.toDp() } >= minWidthDp.dp
}
