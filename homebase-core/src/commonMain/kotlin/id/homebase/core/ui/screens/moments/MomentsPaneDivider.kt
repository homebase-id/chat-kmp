package id.homebase.core.ui.screens.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import id.homebase.core.util.horizontalResizeCursor
import id.homebase.resources.MR
import id.homebase.resources.moments_resize_panes
import org.jetbrains.compose.resources.stringResource

/**
 * The drag handle between the feed and detail panes. Dragging, the arrow keys and the
 * accessibility set-progress action all resize the feed via [onResizeBy]; [onResizeFinished]
 * marks the end of a gesture so the caller can persist once instead of per frame.
 */
@Composable
internal fun MomentsPaneDivider(
    feedWidth: Dp,
    range: ClosedRange<Dp>,
    onResizeBy: (Dp) -> Unit,
    onResizeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentOnResizeBy by rememberUpdatedState(onResizeBy)
    val interactions = remember { MutableInteractionSource() }
    val dragged by interactions.collectIsDraggedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val description = stringResource(MR.string.moments_resize_panes)
    // M3's VerticalDragHandle animates off hover, which keeps the Skiko render loop busy while idle.
    val gripColor = if (dragged || focused) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = modifier
            .width(MomentsLayoutSpec.DetailPaneGutter)
            .fillMaxHeight()
            .horizontalResizeCursor()
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = feedWidth.value,
                    range = range.start.value..range.endInclusive.value,
                )
                setProgress { target ->
                    onResizeBy(target.dp - feedWidth)
                    onResizeFinished()
                    true
                }
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val grow = when (event.key) {
                    Key.DirectionRight -> !rtl
                    Key.DirectionLeft -> rtl
                    else -> return@onKeyEvent false
                }
                val step = MomentsLayoutSpec.FeedPaneResizeStep
                onResizeBy(if (grow) step else -step)
                onResizeFinished()
                true
            }
            .focusable(interactionSource = interactions)
            .draggable(
                state = rememberDraggableState { deltaPx ->
                    currentOnResizeBy(with(density) { deltaPx.toDp() })
                },
                orientation = Orientation.Horizontal,
                reverseDirection = rtl,
                interactionSource = interactions,
                onDragStopped = { onResizeFinished() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 4.dp, height = 48.dp).background(gripColor, CircleShape))
    }
}
