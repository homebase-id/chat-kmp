package id.homebase.core.widget

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WithTooltip(
    text: String,
    modifier: Modifier = Modifier,
    // A Boolean, not TooltipAnchorPosition: that type is experimental and would make every caller opt in.
    below: Boolean = false,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(if (below) TooltipAnchorPosition.Below else TooltipAnchorPosition.Above),
        tooltip = {
            var area by remember { mutableStateOf<Rect?>(null) }
            ReportOverlay(area)
            PlainTooltip(Modifier.onGloballyPositioned { area = it.boundsInWindow() }) { Text(text) }
        },
        state = rememberTooltipState(),
        modifier = modifier,
        content = content,
    )
}
