package id.homebase.core.ui.screens.location.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.core.location.LocationMapProvider
import id.homebase.core.location.LocationPreferences
import id.homebase.core.ui.screens.location.devices.LocationDeviceDirectory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

class LocationHistoryViewModel(
    private val deviceDirectory: LocationDeviceDirectory,
    private val locationPreferences: LocationPreferences,
) : ViewModel() {

    private val logger = Logger.withTag("LocationHistoryViewModel")

    private val _uiState = MutableStateFlow(
        LocationHistoryUiState(
            dayStartMs = localDayStart(Clock.System.now().toEpochMilliseconds()),
            showMapTiles = locationPreferences.mapProvider.value == LocationMapProvider.OpenStreetMap,
        )
    )
    val uiState: StateFlow<LocationHistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            locationPreferences.mapProvider.collect { provider ->
                _uiState.update {
                    it.copy(showMapTiles = provider == LocationMapProvider.OpenStreetMap)
                }
            }
        }
        loadDay(_uiState.value.dayStartMs)
    }

    fun onAction(action: LocationHistoryUiAction) {
        when (action) {
            is LocationHistoryUiAction.SelectDay -> loadDay(localDayStart(action.dayStartMs))
            LocationHistoryUiAction.PreviousDay -> loadDay(shiftDay(_uiState.value.dayStartMs, -1))
            LocationHistoryUiAction.NextDay -> loadDay(shiftDay(_uiState.value.dayStartMs, 1))
        }
    }

    private fun loadDay(dayStartMs: Long) {
        _uiState.update { it.copy(dayStartMs = dayStartMs, isLoading = true) }
        viewModelScope.launch {
            val dayEndMs = shiftDay(dayStartMs, 1)
            val traces = runCatching { deviceDirectory.loadDayTraces(dayStartMs, dayEndMs) }
                .onFailure { logger.e(it) { "loadDayTraces failed for $dayStartMs" } }
                .getOrDefault(emptyList())
            val playback = DayPlayback.build(traces)
            _uiState.update {
                // Drop stale results if the user already moved to another day.
                if (it.dayStartMs != dayStartMs) it
                else it.copy(
                    traces = traces,
                    stats = LocationHistoryAssembler.stats(traces),
                    playback = playback,
                    isLoading = false,
                )
            }
        }
    }
}
