package id.homebase.chat.widget.video

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import id.homebase.api.video.IndexedFrame
import id.homebase.resources.MR
import id.homebase.resources.trim_end_handle
import id.homebase.resources.trim_position
import id.homebase.resources.trim_start_handle
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private const val MIN_RANGE_MS_DEFAULT = 500L
private val HANDLE_WIDTH = 16.dp
private val SCRUBBER_HEIGHT = 56.dp

/**
 * Dual-handle range selector with a thumbnail strip and a playback cursor.
 * Frames in [frames] are slotted by [IndexedFrame.index] (0..[frameCount]-1) and rendered
 * as they become available; missing slots show as a dim placeholder so the strip
 * fills in progressively.
 */
@Composable
fun VideoTrimScrubber(
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    playheadMs: Long,
    frames: Map<Int, IndexedFrame>,
    frameCount: Int,
    onTrimChange: (startMs: Long, endMs: Long) -> Unit,
    onPlayheadChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    minRangeMs: Long = MIN_RANGE_MS_DEFAULT,
) {
    val density = LocalDensity.current
    var trackWidthPx by remember { mutableStateOf(0) }
    val handleWidthPx = with(density) { HANDLE_WIDTH.toPx() }
    val effectiveWidthPx = (trackWidthPx - 2 * handleWidthPx).coerceAtLeast(1f)

    fun msToPx(ms: Long): Float =
        if (durationMs <= 0) 0f
        else handleWidthPx + (ms.toFloat() / durationMs.toFloat()) * effectiveWidthPx

    fun pxToMs(px: Float): Long {
        val clamped = (px - handleWidthPx).coerceIn(0f, effectiveWidthPx)
        return (clamped / effectiveWidthPx * durationMs).roundToInt().toLong()
    }

    val startHandleLabel = stringResource(MR.string.trim_start_handle)
    val endHandleLabel = stringResource(MR.string.trim_end_handle)
    val positionLabel = stringResource(MR.string.trim_position)

    // Live state for the long-lived gesture lambdas. detectDragGestures captures
    // values at lambda-creation time, but startMs/endMs flip with every drag event
    // we dispatch — so we have to read them via State to see the latest.
    val startMsState = rememberUpdatedState(startMs)
    val endMsState = rememberUpdatedState(endMs)
    val effWidthState = rememberUpdatedState(effectiveWidthPx)
    val handleWidthState = rememberUpdatedState(handleWidthPx)
    val onTrimChangeState = rememberUpdatedState(onTrimChange)
    val onPlayheadChangeState = rememberUpdatedState(onPlayheadChange)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SCRUBBER_HEIGHT)
            .onSizeChanged { trackWidthPx = it.width }
            .pointerInput(durationMs) {
                // Gesture lives on the *stationary* parent. The handle Boxes move
                // (via .offset) when state updates, so attaching the gesture there
                // makes dragAmount collapse to zero in the moving local frame —
                // hence the previous "barely drags" bug. Here, change.position is
                // in the parent's coords and keeps tracking the finger correctly.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downX = down.position.x
                    val hw = handleWidthState.value
                    val eff = effWidthState.value
                    val total = trackWidthPx.toFloat()
                    if (total <= 0f || eff <= 0f) return@awaitEachGesture
                    val startScreenPx = if (durationMs > 0)
                        hw + (startMsState.value.toFloat() / durationMs.toFloat()) * eff
                    else hw
                    val endScreenPx = if (durationMs > 0)
                        hw + (endMsState.value.toFloat() / durationMs.toFloat()) * eff
                    else (total - hw)
                    val hit = hw * 1.5f
                    val mode: Int = when {
                        kotlin.math.abs(downX - startScreenPx) < hit -> 0 // MIN
                        kotlin.math.abs(downX - endScreenPx) < hit -> 1   // MAX
                        else -> -1
                    }
                    if (mode == -1) return@awaitEachGesture
                    down.consume()
                    val anchorPx = if (mode == 0) startScreenPx else endScreenPx
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        val deltaSinceDown = change.position.x - down.position.x
                        val rawPx = anchorPx + deltaSinceDown
                        val clampedPx = (rawPx - hw).coerceIn(0f, eff)
                        val newMs = (clampedPx / eff * durationMs).roundToInt().toLong()
                        if (mode == 0) {
                            val newStart = newMs
                                .coerceAtLeast(0L)
                                .coerceAtMost(endMsState.value - minRangeMs)
                            onTrimChangeState.value(newStart, endMsState.value)
                            onPlayheadChangeState.value(newStart)
                        } else {
                            val newEnd = newMs
                                .coerceAtMost(durationMs)
                                .coerceAtLeast(startMsState.value + minRangeMs)
                            onTrimChangeState.value(startMsState.value, newEnd)
                            onPlayheadChangeState.value(
                                (newEnd - 100L).coerceAtLeast(startMsState.value)
                            )
                        }
                    }
                }
            }
    ) {
        // Thumbnail strip — Row of equal-width slots, fills in as frames arrive
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = HANDLE_WIDTH)
                .clip(RoundedCornerShape(4.dp)),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            for (i in 0 until frameCount) {
                val frame = frames[i]
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    if (frame != null) {
                        AsyncImage(
                            model = frame.jpegBytes,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }

        // Greyed overlay before start
        val startPx = msToPx(startMs)
        Box(
            modifier = Modifier
                .padding(start = HANDLE_WIDTH)
                .width(with(density) { (startPx - handleWidthPx).coerceAtLeast(0f).toDp() })
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.6f))
        )

        // Greyed overlay after end
        val endPx = msToPx(endMs)
        val tailWidthPx = (trackWidthPx - handleWidthPx) - endPx
        Box(
            modifier = Modifier
                .offset { IntOffset(endPx.roundToInt(), 0) }
                .width(with(density) { tailWidthPx.coerceAtLeast(0f).toDp() })
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.6f))
        )

        // Position cursor (1.5 dp white line)
        val playheadPx = msToPx(playheadMs.coerceIn(startMs, endMs))
        Box(
            modifier = Modifier
                .offset { IntOffset((playheadPx - 1).roundToInt(), 0) }
                .width(2.dp)
                .fillMaxHeight()
                .background(Color.White)
                .semantics { contentDescription = positionLabel }
        )

        // MIN handle (left) — visual only; gesture is on the parent Box
        TrimHandle(
            label = startHandleLabel,
            modifier = Modifier.offset { IntOffset((startPx - handleWidthPx).roundToInt(), 0) },
        )

        // MAX handle (right) — visual only
        TrimHandle(
            label = endHandleLabel,
            modifier = Modifier.offset { IntOffset(endPx.roundToInt(), 0) },
        )
    }
}

@Composable
private fun TrimHandle(
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(HANDLE_WIDTH)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(20.dp)
                .background(MaterialTheme.colorScheme.onPrimary)
        )
    }
}

/** Helper formatter shared by the trim screen toolbar. */
internal fun formatDurationLabel(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

@Composable
internal fun TrimDurationLabel(startMs: Long, endMs: Long, totalMs: Long) {
    Text(
        text = "${formatDurationLabel(endMs - startMs)} / ${formatDurationLabel(totalMs)}",
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
