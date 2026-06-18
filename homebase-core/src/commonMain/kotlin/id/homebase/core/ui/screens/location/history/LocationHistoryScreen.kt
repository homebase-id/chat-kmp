package id.homebase.core.ui.screens.location.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.util.formatMediumDate
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.location_history_next_day
import id.homebase.resources.location_history_pick_date
import id.homebase.resources.location_history_previous_day
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
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationHistoryScreen(
    viewModel: LocationHistoryViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    val deviceTz = remember { TimeZone.currentSystemDefault() }

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

            // The map, dwell dots, stats and 24h scrubber are the reusable player.
            DayPlaybackMap(
                traces = uiState.traces,
                dayStartMs = uiState.dayStartMs,
                showMapTiles = uiState.showMapTiles,
                isLoading = uiState.isLoading,
                modifier = Modifier.weight(1f),
            )
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
