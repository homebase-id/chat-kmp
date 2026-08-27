package id.homebase.core.util

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass

// Both the navigation rail and the list/detail scaffolds must read this same gate —
// if they diverge a tablet gets a rail beside a stretched single column.
@Composable
fun isExpandedLayout(): Boolean =
    currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND
    )
