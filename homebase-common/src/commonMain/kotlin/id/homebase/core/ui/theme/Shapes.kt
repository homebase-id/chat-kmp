package id.homebase.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

// Shared by the desktop navigation rail and the settings pane's sidebar. Both are navigation
// surfaces sitting side by side, so a selected item shaped differently in each reads as a bug.
val NavigationIndicatorShape: Shape = RoundedCornerShape(14.dp)
