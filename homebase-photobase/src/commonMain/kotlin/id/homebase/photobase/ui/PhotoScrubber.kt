package id.homebase.photobase.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import id.homebase.core.haptics.HapticEvent
import id.homebase.core.haptics.Haptics
import id.homebase.photobase.gallery.GalleryMonth
import id.homebase.photobase.gallery.GalleryRows
import id.homebase.resources.MR
import id.homebase.resources.photobase_month_1
import id.homebase.resources.photobase_month_10
import id.homebase.resources.photobase_month_11
import id.homebase.resources.photobase_month_12
import id.homebase.resources.photobase_month_2
import id.homebase.resources.photobase_month_3
import id.homebase.resources.photobase_month_4
import id.homebase.resources.photobase_month_5
import id.homebase.resources.photobase_month_6
import id.homebase.resources.photobase_month_7
import id.homebase.resources.photobase_month_8
import id.homebase.resources.photobase_month_9
import id.homebase.resources.photobase_scrubber
import id.homebase.resources.photobase_scrubber_label
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private val HANDLE_WIDTH = 32.dp
private val HANDLE_HEIGHT = 48.dp

// Wide enough for a thumb to find the handle at the screen's edge.
private val TOUCH_WIDTH = 48.dp
private val YEAR_MARK_GAP = 32.dp
private val YEAR_MARK_INSET = 40.dp
private val BUBBLE_WIDTH = 160.dp
private const val HIDE_AFTER_MILLIS = 1_500L

// Below about three screens there's nowhere far to go.
private const val SCREENS_TO_SHOW = 3

private val MONTHS = listOf(
    MR.string.photobase_month_1, MR.string.photobase_month_2, MR.string.photobase_month_3, MR.string.photobase_month_4,
    MR.string.photobase_month_5, MR.string.photobase_month_6, MR.string.photobase_month_7, MR.string.photobase_month_8,
    MR.string.photobase_month_9, MR.string.photobase_month_10, MR.string.photobase_month_11, MR.string.photobase_month_12,
)

/**
 * A handle on the grid's edge that moves through the whole library in proportion to the grid's height, showing the
 * month and year under it. It appears while the grid scrolls, the handle is dragged or a mouse is near, and moves by
 * how far it's dragged, never jumping to where the finger or pointer lands.
 */
@Composable
internal fun PhotoScrubber(
    gridState: LazyGridState,
    rows: GalleryRows,
    pointerNear: Boolean,
    haptics: Haptics,
    modifier: Modifier = Modifier,
) {
    val viewport = gridState.layoutInfo.viewportSize.height
    val scrollable = (rows.height - viewport).coerceAtLeast(0)
    if (viewport <= 0 || rows.height < viewport * SCREENS_TO_SHOW) return
    var dragging by remember { mutableStateOf(false) }
    var handleTop by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    val scrolling = gridState.isScrollInProgress
    LaunchedEffect(scrolling, dragging, pointerNear) {
        if (scrolling || dragging || pointerNear) {
            shown = true
        } else {
            delay(HIDE_AFTER_MILLIS)
            shown = false
        }
    }
    BoxWithConstraints(modifier.fillMaxHeight().width(TOUCH_WIDTH)) {
        val density = LocalDensity.current
        val travel = (constraints.maxHeight - with(density) { HANDLE_HEIGHT.toPx() }).coerceAtLeast(1f)
        // Where the grid is, while it isn't being dragged from here.
        val fromGrid by remember(rows, travel, scrollable) {
            derivedStateOf {
                val y = rows.topOf(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                if (scrollable == 0) 0f else (y.toFloat() / scrollable).coerceIn(0f, 1f) * travel
            }
        }
        val top = if (dragging) handleTop else fromGrid
        val target = if (scrollable == 0) 0 else (top / travel * scrollable).roundToInt()
        val month = rows.monthAt(target)
        // The grid follows the handle at its own pace: a scroll per drag event would queue up behind each other.
        LaunchedEffect(gridState, rows, scrollable, travel) {
            snapshotFlow { if (dragging) (handleTop / travel * scrollable).roundToInt() else null }
                .filterNotNull()
                .collect { y ->
                    val (item, offset) = rows.itemAt(y)
                    gridState.scrollToItem(item, offset)
                }
        }
        var lastMonth by remember { mutableStateOf<GalleryMonth?>(null) }
        LaunchedEffect(month, dragging) {
            if (dragging && lastMonth != null && month != lastMonth) haptics.perform(HapticEvent.Selection)
            lastMonth = month
        }
        if (dragging) YearMarks(rows, scrollable, travel, with(density) { YEAR_MARK_GAP.toPx() }, with(density) { HANDLE_HEIGHT.toPx() / 2 })
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.offset { IntOffset(0, top.roundToInt()) },
        ) {
            Box(Modifier.size(TOUCH_WIDTH, HANDLE_HEIGHT)) {
                val description = stringResource(MR.string.photobase_scrubber)
                Surface(
                    shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(HANDLE_WIDTH, HANDLE_HEIGHT)
                        .semantics { contentDescription = description }
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta -> handleTop = (handleTop + delta).coerceIn(0f, travel) },
                            onDragStarted = {
                                handleTop = fromGrid
                                dragging = true
                            },
                            onDragStopped = { dragging = false },
                        ),
                ) {}
            }
        }
        if (dragging && month != null) MonthBubble(month, top)
    }
}

@Composable
private fun MonthBubble(month: GalleryMonth, handleTop: Float) {
    Box(
        Modifier
            .offset { IntOffset(-BUBBLE_WIDTH.roundToPx(), handleTop.roundToInt()) }
            .width(BUBBLE_WIDTH),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 4.dp,
        ) {
            Text(
                text = stringResource(MR.string.photobase_scrubber_label, stringResource(MONTHS[month.month - 1]), month.year),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

// Where each year starts on the track, skipping one too close to the last shown.
@Composable
private fun YearMarks(rows: GalleryRows, scrollable: Int, travel: Float, gapPx: Float, centrePx: Float) {
    val marks = remember(rows, scrollable, travel) {
        var last = Float.NEGATIVE_INFINITY
        rows.months.zipWithNext().mapNotNull { (before, month) -> month.takeIf { it.year != before.year } }
            .let { listOfNotNull(rows.months.firstOrNull()) + it }
            .mapNotNull { month ->
                val y = if (scrollable == 0) 0f else (month.top.toFloat() / scrollable).coerceIn(0f, 1f) * travel + centrePx
                if (y - last < gapPx) null else (month.year to y).also { last = y }
            }
    }
    for ((year, y) in marks) {
        Text(
            text = year.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .offset { IntOffset(-YEAR_MARK_INSET.roundToPx(), y.roundToInt()) }
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp),
        )
    }
}
