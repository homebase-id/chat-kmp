package id.homebase.core.ui.screens.location.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.common.time.UnixTimeUtcRange
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.QueryBatch
import id.homebase.core.config.locationLabeledDrive
import id.homebase.core.location.LocationPreferences
import id.homebase.core.location.tracking.LocationDeviceId
import id.homebase.core.ui.screens.location.model.HOUR_MS
import id.homebase.core.ui.screens.location.model.LOCATION_TRACK_FILE_TYPE
import id.homebase.core.ui.screens.location.model.LocationTrackCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

class LocationHistoryViewModel(
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
    private val locationPreferences: LocationPreferences,
    private val deviceId: LocationDeviceId,
) : ViewModel() {

    private val logger = Logger.withTag("LocationHistoryViewModel")
    private val driveId = locationLabeledDrive.drive.alias

    private val _uiState = MutableStateFlow(
        LocationHistoryUiState(
            dayStartMs = localDayStart(Clock.System.now().toEpochMilliseconds()),
            showMapTiles = locationPreferences.showMapTiles.value,
        )
    )
    val uiState: StateFlow<LocationHistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            locationPreferences.showMapTiles.collect { show ->
                _uiState.update { it.copy(showMapTiles = show) }
            }
        }
        loadDay(_uiState.value.dayStartMs)
    }

    fun onAction(action: LocationHistoryUiAction) {
        when (action) {
            is LocationHistoryUiAction.SelectDay -> loadDay(localDayStart(action.dayStartMs))
            LocationHistoryUiAction.PreviousDay -> loadDay(shiftDay(_uiState.value.dayStartMs, -1))
            LocationHistoryUiAction.NextDay -> loadDay(shiftDay(_uiState.value.dayStartMs, 1))
            is LocationHistoryUiAction.SetShowMapTiles -> viewModelScope.launch {
                locationPreferences.setShowMapTiles(action.show)
            }
        }
    }

    private fun loadDay(dayStartMs: Long) {
        _uiState.update { it.copy(dayStartMs = dayStartMs, isLoading = true) }
        viewModelScope.launch {
            val dayEndMs = shiftDay(dayStartMs, 1)
            val traces = runCatching { loadTraces(dayStartMs, dayEndMs) }
                .onFailure { logger.e(it) { "loadTraces failed for $dayStartMs" } }
                .getOrDefault(emptyList())
            _uiState.update {
                // Drop stale results if the user already moved to another day.
                if (it.dayStartMs != dayStartMs) it
                else it.copy(traces = traces, stats = LocationHistoryAssembler.stats(traces), isLoading = false)
            }
        }
    }

    private suspend fun loadTraces(dayStartMs: Long, dayEndMs: Long): List<DeviceTrace> {
        val creds = credentialsManager.getActiveCredentials() ?: return emptyList()
        val result = QueryBatch(creds.getIdentityId()).queryBatchAsync(
            dbm = databaseManager,
            driveId = driveId,
            noOfItems = 24 * 16, // a day of hour files for up to 16 devices
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.UserDate,
            fileSystemType = FileSystemType.Standard.value,
            filetypesAnyOf = listOf(LOCATION_TRACK_FILE_TYPE),
            // userDate = UTC hour start; widen by an hour on the left so an hour
            // file straddling the local-day boundary is included (its points are
            // filtered to the day by the assembler).
            userDateSpan = UnixTimeUtcRange(
                start = UnixTimeUtc(dayStartMs - HOUR_MS),
                end = UnixTimeUtc(dayEndMs),
            ),
        )
        val hours = result.records.mapNotNull { file ->
            file.fileMetadata.appData.content?.let { LocationTrackCodec.decodeHeader(it) }
        }
        val bufferPoints = databaseManager.locationPoint.selectByTimeRange(dayStartMs, dayEndMs)
        return LocationHistoryAssembler.assemble(
            hours = hours,
            bufferPoints = bufferPoints,
            bufferDeviceId = deviceId.value,
            dayStartMs = dayStartMs,
            dayEndMs = dayEndMs,
        )
    }

    /** Device-local midnight of the day containing [epochMs]. */
    private fun localDayStart(epochMs: Long): Long {
        val tz = TimeZone.currentSystemDefault()
        return Instant.fromEpochMilliseconds(epochMs)
            .toLocalDateTime(tz).date
            .atStartOfDayIn(tz)
            .toEpochMilliseconds()
    }

    /** Local midnight [days] away — DST-safe via LocalDate arithmetic. */
    private fun shiftDay(dayStartMs: Long, days: Int): Long {
        val tz = TimeZone.currentSystemDefault()
        return Instant.fromEpochMilliseconds(dayStartMs)
            .toLocalDateTime(tz).date
            .plus(days, DateTimeUnit.DAY)
            .atStartOfDayIn(tz)
            .toEpochMilliseconds()
    }
}
