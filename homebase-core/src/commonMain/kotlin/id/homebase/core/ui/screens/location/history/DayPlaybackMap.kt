package id.homebase.core.ui.screens.location.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import id.homebase.api.client.location.LocationPreviewProvider
import id.homebase.api.sync.database.BufferedLocationPoint
import id.homebase.core.util.formatTime
import id.homebase.resources.MR
import id.homebase.resources.location_history_devices
import id.homebase.resources.location_history_distance
import id.homebase.resources.location_history_empty
import id.homebase.resources.location_history_enable_tracking
import id.homebase.resources.location_history_points
import id.homebase.resources.location_history_span
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Releasing the scrubber within this fraction of the left edge counts as a
// "rewind to start" and replays the animated sweep.
private const val REWIND_REPLAY_FRACTION = 0.02f
private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * Reusable animated playback of **one day** of GPS movement: an inset map that
 * auto-plays a time-warped sweep once (slow through movement, fast through idle),
 * dwell dots, a day-stats row, and a 24-hour scrubber (drag to a time to inspect
 * it; release at the far left to replay). Self-contained — owns its own clock and
 * tile provider — so it can show the local History day, or any pulled day from
 * another identity (e.g. a family member during an emergency).
 *
 * [subjectName], when set, is shown as a header so the viewer is sure whose day
 * they're looking at — pass the person's name for pulled data, null for one's own.
 *
 * @param dayStartMs device-local midnight of the shown day (anchors the 24h slider).
 */
@Composable
fun DayPlaybackMap(
    traces: List<DeviceTrace>,
    dayStartMs: Long,
    showMapTiles: Boolean,
    modifier: Modifier = Modifier,
    subjectName: String? = null,
    isLoading: Boolean = false,
    /** Whether personal location history is on. Drives the empty-state "turn on tracking" hint. */
    allowLocationHistory: Boolean = true,
    /** Tap target for the empty-state "turn on location tracking" link. Null ⇒ no link (the link is
     *  only meaningful for one's own history, so non-history callers leave it null). */
    onEnableTracking: (() -> Unit)? = null,
) {
    val previewProvider = koinInject<LocationPreviewProvider>()
    // Playback model + stats derive from the day's points (cheap, O(points)).
    val playback = remember(traces) { DayPlayback.build(traces) }
    val stats = remember(traces) { LocationHistoryAssembler.stats(traces) }
    val isEmpty = !isLoading && traces.isEmpty()

    // ── Playback / scrubber state (reset per day) ──
    // `clockMs` is the single source of truth: the day-instant the map renders.
    var clockMs by remember(dayStartMs) { mutableLongStateOf(dayStartMs) }
    var isScrubbing by remember(dayStartMs) { mutableStateOf(false) }
    var autoPlayed by remember(dayStartMs) { mutableStateOf(false) }
    // Bumped to replay the sweep (rewind-to-start gesture); part of the effect key.
    var replayToken by remember(dayStartMs) { mutableIntStateOf(0) }

    // Auto-play once per day (and again on each replay): a warped sweep.
    LaunchedEffect(dayStartMs, playback, replayToken) {
        if (playback == null || autoPlayed || isScrubbing) return@LaunchedEffect
        autoPlayed = true
        clockMs = playback.firstFixMs
        val total = playback.totalAnimMillis.toLong().coerceAtLeast(1L)
        val startNanos = withFrameNanos { it }
        while (!isScrubbing) {
            val now = withFrameNanos { it }
            val progress = (((now - startNanos) / 1_000_000).toFloat() / total).coerceIn(0f, 1f)
            clockMs = playback.clockAtProgress(progress)
            if (progress >= 1f) break
        }
        if (!isScrubbing) clockMs = playback.lastFixMs
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (subjectName != null) {
            Text(
                text = subjectName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                textAlign = TextAlign.Center,
            )
        }

        // ── Trace canvas ──
        // Card clips and insets the map so it doesn't run into whatever sits
        // above it or the stats/scrubber below.
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LocationTraceCanvas(
                    traces = traces,
                    showMapTiles = showMapTiles,
                    fetchTile = { z, x, y -> previewProvider.getTilePng(z, x, y) },
                    traceColors = mapTraceColors,
                    playbackClockMs = if (playback != null) clockMs else null,
                    dwellStops = playback?.stops ?: emptyList(),
                )
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (isEmpty) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(MR.string.location_history_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        // Only when this is the user's own history (onEnableTracking provided) and the
                        // day is empty *because* tracking is off — nudge them to turn it on.
                        if (onEnableTracking != null && !allowLocationHistory) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(MR.string.location_history_enable_tracking),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.clickable { onEnableTracking() },
                            )
                        }
                    }
                }
            }
        }

        // ── Stats ──
        stats.takeIf { it.pointCount > 0 }?.let { s ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(
                        MR.string.location_history_distance,
                        ((s.distanceMeters / 100.0).roundToInt() / 10.0).toString(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (s.firstFixMs != null && s.lastFixMs != null) {
                    Text(
                        text = stringResource(
                            MR.string.location_history_span,
                            formatTime(Instant.fromEpochMilliseconds(s.firstFixMs)),
                            formatTime(Instant.fromEpochMilliseconds(s.lastFixMs)),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = stringResource(MR.string.location_history_points, s.pointCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(MR.string.location_history_devices, s.deviceCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // ── 24h scrubber (the only playback control) ──
        if (playback != null) {
            Text(
                text = formatTime(Instant.fromEpochMilliseconds(clockMs)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                textAlign = TextAlign.Center,
            )
            Slider(
                value = ((clockMs - dayStartMs).toFloat() / DAY_MS).coerceIn(0f, 1f),
                onValueChange = { v ->
                    isScrubbing = true
                    clockMs = dayStartMs + (v * DAY_MS).toLong()
                },
                onValueChangeFinished = {
                    isScrubbing = false
                    // Released at the far left → rewind and replay the sweep.
                    val frac = (clockMs - dayStartMs).toFloat() / DAY_MS
                    if (frac <= REWIND_REPLAY_FRACTION) {
                        autoPlayed = false
                        replayToken++
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Convenience overload taking one subject's flat day-point list (as the emergency
 * feature would pull for a family member) — gap-segments it into a single trace and
 * plays it back. [subjectName] is shown as the header. Points keep `steps`/`bat`.
 */
@Composable
fun DayPlaybackMap(
    points: List<BufferedLocationPoint>,
    subjectId: Uuid,
    dayStartMs: Long,
    showMapTiles: Boolean,
    modifier: Modifier = Modifier,
    subjectName: String? = null,
    isLoading: Boolean = false,
) {
    val traces = remember(points, subjectId) {
        LocationHistoryAssembler.singleDeviceTraces(points, subjectId)
    }
    DayPlaybackMap(
        traces = traces,
        dayStartMs = dayStartMs,
        showMapTiles = showMapTiles,
        modifier = modifier,
        subjectName = subjectName,
        isLoading = isLoading,
    )
}
