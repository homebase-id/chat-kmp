package id.homebase.core.util

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable

private const val ExpandedWidthBreakpointDp = 800

/** Wide phones and tablets stay single-column: their touch targets and FAB placement
 *  assume one viewport, so the split is limited to pointer-driven targets. */
@Composable
fun isExpandedLayout(): Boolean =
    isDesktopOrWeb() &&
        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
            ExpandedWidthBreakpointDp
        )
