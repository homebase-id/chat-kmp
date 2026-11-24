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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import id.homebase.core.haptics.HapticEvent
import id.homebase.core.haptics.Haptics
import id.homebase.core.util.formatMonthYear
import id.homebase.photobase.gallery.GalleryLayout
import id.homebase.photobase.gallery.GalleryMonth
import id.homebase.photobase.gallery.GalleryRows
import id.homebase.resources.MR
import id.homebase.resources.photobase_scrubber
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

internal const val DAY_HEADER = "day"
internal const val PHOTO_TILE = "photo"

private val HANDLE_WIDTH = 32.dp
private val HANDLE_HEIGHT = 48.dp

// Wide enough for a thumb to find the handle at the screen's edge; also where a mouse shows it.
private val EDGE_WIDTH = 48.dp
private val YEAR_MARK_GAP = 32.dp
private val YEAR_MARK_INSET = 40.dp
private val BUBBLE_WIDTH = 160.dp
private const val HIDE_AFTER_MILLIS = 1_500L

// Below about three screens there's nowhere far to go.
private const val SCREENS_TO_SHOW = 3

/**
 * The [grid], with a handle on its edge that moves through the whole library in proportion to the grid's height,
 * showing the month and year at the top of the grid. It appears while the grid scrolls, the handle is dragged or a
 * mouse is near, and moves by how far it's dragged, never jumping to where the finger or pointer lands.
 */
@Composable
internal fun PhotoScrubber(
    gridState: LazyGridState,
    layout: GalleryLayout,
    haptics: Haptics,
    grid: @Composable () -> Unit,
) {
    val pitch by rememberRowPitch(gridState)
    val rows = remember(layout, pitch) { pitch?.let { GalleryRows(layout, it.columns, it.headerPx, it.rowPx) } }
    val pointerNear = remember { mutableStateOf(false) }
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // Seen first and never consumed: a mouse near the right edge shows the handle, and the grid still scrolls.
            val near = EDGE_WIDTH.toPx()
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.type == PointerType.Mouse) {
                        pointerNear.value = event.type != PointerEventType.Exit && change.position.x > size.width - near
                    }
                }
            }
        },
    ) {
        grid()
        val viewport = constraints.maxHeight
        val held = rows
        if (held != null && held.height >= viewport * SCREENS_TO_SHOW) {
            Handle(gridState, held, held.height - viewport, pointerNear, haptics, Modifier.align(Alignment.TopEnd))
        }
    }
}

private data class RowPitch(val columns: Int, val headerPx: Int, val rowPx: Int)

// The grid's columns, and a header's and a row's height with the gap after each, from a header with the item after it
// on screen. Kept while none is, as inside a day bigger than the screen.
@Composable
private fun rememberRowPitch(gridState: LazyGridState): State<RowPitch?> {
    val pitch = remember { mutableStateOf<RowPitch?>(null) }
    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val items = info.visibleItemsInfo
            val header = items.firstOrNull { it.contentType == DAY_HEADER } ?: return@snapshotFlow null
            val next = items.firstOrNull { it.index == header.index + 1 } ?: return@snapshotFlow null
            val tile = items.firstOrNull { it.contentType == PHOTO_TILE } ?: return@snapshotFlow null
            val gap = next.offset.y - header.offset.y - header.size.height
            RowPitch(info.maxSpan, next.offset.y - header.offset.y, tile.size.height + gap)
        }.filterNotNull().collect { pitch.value = it }
    }
    return pitch
}

@Composable
private fun Handle(
    gridState: LazyGridState,
    rows: GalleryRows,
    scrollable: Int,
    pointerNear: MutableState<Boolean>,
    haptics: Haptics,
    modifier: Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var handleTop by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    val scrolling = gridState.isScrollInProgress
    LaunchedEffect(scrolling, dragging, pointerNear.value) {
        if (scrolling || dragging || pointerNear.value) {
            shown = true
        } else {
            delay(HIDE_AFTER_MILLIS)
            shown = false
        }
    }
    BoxWithConstraints(modifier.fillMaxHeight().width(EDGE_WIDTH)) {
        val handlePx = with(LocalDensity.current) { HANDLE_HEIGHT.toPx() }
        val travel = (constraints.maxHeight - handlePx).coerceAtLeast(1f)
        val toGrid = { trackY: Float -> (trackY / travel * scrollable).roundToInt() }
        val toTrack = { gridY: Int -> (gridY.toFloat() / scrollable).coerceIn(0f, 1f) * travel }
        // Read in the layout phase only, so following the grid or the finger doesn't recompose this.
        val top = { if (dragging) handleTop else toTrack(rows.topOf(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)) }
        val month by remember(rows, travel) { derivedStateOf { if (dragging) rows.monthAt(toGrid(handleTop)) else null } }
        LaunchedEffect(month) {
            if (month != null) haptics.perform(HapticEvent.Selection)
        }
        if (dragging) YearMarks(rows) { toTrack(it) + handlePx / 2 }
        val description = stringResource(MR.string.photobase_scrubber)
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.offset { IntOffset(0, top().roundToInt()) },
        ) {
            Box(
                Modifier
                    .size(EDGE_WIDTH, HANDLE_HEIGHT)
                    .semantics { contentDescription = description }
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            handleTop = (handleTop + delta).coerceIn(0f, travel)
                            val (item, offset) = rows.itemAt(toGrid(handleTop))
                            // Not scrollToItem: that measures the grid on each pointer event, several a frame on desktop.
                            gridState.requestScrollToItem(item, offset)
                        },
                        onDragStarted = {
                            handleTop = top()
                            dragging = true
                        },
                        onDragStopped = { dragging = false },
                    ),
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shadowElevation = 2.dp,
                    modifier = Modifier.align(Alignment.CenterEnd).size(HANDLE_WIDTH, HANDLE_HEIGHT),
                ) {}
            }
        }
        month?.let { MonthBubble(it, top) }
    }
}

@Composable
private fun MonthBubble(month: GalleryMonth, top: () -> Float) {
    val label = remember(month) { formatMonthYear(month.first.atStartOfDayIn(TimeZone.currentSystemDefault())) }
    Box(
        Modifier
            .offset { IntOffset(-BUBBLE_WIDTH.roundToPx(), top().roundToInt()) }
            .width(BUBBLE_WIDTH),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shadowElevation = 4.dp,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

// A year too close to the last one shown is skipped.
@Composable
private fun YearMarks(rows: GalleryRows, trackY: (Int) -> Float) {
    val gapPx = with(LocalDensity.current) { YEAR_MARK_GAP.toPx() }
    val marks = remember(rows, gapPx) {
        var last = Float.NEGATIVE_INFINITY
        rows.years.mapNotNull { month ->
            val y = trackY(month.top)
            if (y - last < gapPx) null else (month.first.year to y).also { last = y }
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
