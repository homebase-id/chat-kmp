package id.homebase.core.ui.screens.location.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.location.LocationPreviewProvider
import id.homebase.core.util.formatMediumDate
import id.homebase.core.util.formatTime
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.location_history_devices
import id.homebase.resources.location_history_distance
import id.homebase.resources.location_history_empty
import id.homebase.resources.location_history_next_day
import id.homebase.resources.location_history_pick_date
import id.homebase.resources.location_history_points
import id.homebase.resources.location_history_previous_day
import id.homebase.resources.location_history_span
import id.homebase.resources.location_history_title
import id.homebase.resources.menu_back
import id.homebase.resources.ok
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationHistoryScreen(
    viewModel: LocationHistoryViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val previewProvider = koinInject<LocationPreviewProvider>()
    var showPicker by remember { mutableStateOf(false) }
    val deviceTz = remember { TimeZone.currentSystemDefault() }

    // ── Playback / scrubber state (reset per day) ──
    val playback = uiState.playback
    val dayMs = 24L * 60 * 60 * 1000
    // `clockMs` is the single source of truth: the day-instant the map renders.
    var clockMs by remember(uiState.dayStartMs) { mutableLongStateOf(uiState.dayStartMs) }
    var isScrubbing by remember(uiState.dayStartMs) { mutableStateOf(false) }
    var autoPlayed by remember(uiState.dayStartMs) { mutableStateOf(false) }

    // Auto-play once per day: a warped sweep (slow through movement, fast through idle).
    LaunchedEffect(uiState.dayStartMs, playback) {
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.location_history_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding),
        ) {
            // ── Day navigation ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.onAction(LocationHistoryUiAction.PreviousDay) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(MR.string.location_history_previous_day),
                    )
                }
                AssistChip(
                    onClick = { showPicker = true },
                    label = { Text(formatMediumDate(Instant.fromEpochMilliseconds(uiState.dayStartMs))) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.CalendarToday,
                            contentDescription = stringResource(MR.string.location_history_pick_date),
                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                )
                IconButton(onClick = { viewModel.onAction(LocationHistoryUiAction.NextDay) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(MR.string.location_history_next_day),
                    )
                }
            }

            // ── Trace canvas ──
            // Card clips and insets the map so it doesn't run into the day-nav
            // controls above or the stats/scrubber below.
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    LocationTraceCanvas(
                        traces = uiState.traces,
                        showMapTiles = uiState.showMapTiles,
                        fetchTile = { z, x, y -> previewProvider.getTilePng(z, x, y) },
                        traceColors = mapTraceColors,
                        playbackClockMs = if (playback != null) clockMs else null,
                        dwellStops = playback?.stops ?: emptyList(),
                    )
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (uiState.isEmpty) {
                        Text(
                            text = stringResource(MR.string.location_history_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    }
                }
            }

            // ── Stats ──
            uiState.stats?.takeIf { it.pointCount > 0 }?.let { stats ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(
                            MR.string.location_history_distance,
                            ((stats.distanceMeters / 100.0).roundToInt() / 10.0).toString(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (stats.firstFixMs != null && stats.lastFixMs != null) {
                        Text(
                            text = stringResource(
                                MR.string.location_history_span,
                                formatTime(Instant.fromEpochMilliseconds(stats.firstFixMs)),
                                formatTime(Instant.fromEpochMilliseconds(stats.lastFixMs)),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = stringResource(MR.string.location_history_points, stats.pointCount),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(MR.string.location_history_devices, stats.deviceCount),
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
                    value = ((clockMs - uiState.dayStartMs).toFloat() / dayMs).coerceIn(0f, 1f),
                    onValueChange = { v ->
                        isScrubbing = true
                        clockMs = uiState.dayStartMs + (v * dayMs).toLong()
                    },
                    onValueChangeFinished = { isScrubbing = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }

    if (showPicker) {
        // DatePicker thinks in UTC midnight (see MomentDateChip for the full
        // rationale); seed from the shown local day and translate the pick
        // back through local noon so tz boundaries can't flip the day.
        val initialMs = remember(uiState.dayStartMs) {
            val d = Instant.fromEpochMilliseconds(uiState.dayStartMs).toLocalDateTime(deviceTz)
            LocalDate(d.year, d.month, d.day)
                .atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMs)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val ms = state.selectedDateMillis ?: initialMs
                    val ldtUtc = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC)
                    val pickedLocalNoon = LocalDateTime(
                        year = ldtUtc.year, month = ldtUtc.month, day = ldtUtc.day,
                        hour = 12, minute = 0, second = 0,
                    )
                    viewModel.onAction(
                        LocationHistoryUiAction.SelectDay(
                            pickedLocalNoon.toInstant(deviceTz).toEpochMilliseconds()
                        )
                    )
                    showPicker = false
                }) { Text(stringResource(MR.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(MR.string.cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}
