package id.homebase.core.ui.screens.location.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.core.location.LocationMapProvider
import id.homebase.core.location.LocationPreferences
import id.homebase.core.location.emergency.EmergencyLocateStore
import id.homebase.core.ui.screens.location.devices.LocationDeviceDirectory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * @param peerDomain non-null = PEER mode: render a contact's emergency-retrieved history from
 *   the memory-only [EmergencyLocateStore] instead of the own on-device/drive data. Read-only
 *   (day delete is a no-op) and purely local — no network, no DB. The initial day is the day
 *   of the newest retrieved point so the viewer opens on data, not an empty today.
 */
class LocationHistoryViewModel(
    private val deviceDirectory: LocationDeviceDirectory,
    private val locationPreferences: LocationPreferences,
    private val emergencyLocateStore: EmergencyLocateStore,
    private val peerDomain: String? = null,
) : ViewModel() {

    private val logger = Logger.withTag("LocationHistoryViewModel")

    private val _uiState = MutableStateFlow(
        LocationHistoryUiState(
            dayStartMs = localDayStart(initialEpochMs()),
            showMapTiles = locationPreferences.mapProvider.value == LocationMapProvider.OpenStreetMap,
            // Peer mode: the empty state must never show the own-tracking CTA.
            allowLocationHistory = peerDomain != null ||
                locationPreferences.allowLocationHistory.value,
        )
    )
    val uiState: StateFlow<LocationHistoryUiState> = _uiState.asStateFlow()

    private fun initialEpochMs(): Long {
        val nowMs = Clock.System.now().toEpochMilliseconds()
        if (peerDomain == null) return nowMs
        val newestPointMs = emergencyLocateStore[peerDomain]
            ?.hours?.maxOfOrNull { hour -> hour.points.maxOfOrNull { it.t } ?: Long.MIN_VALUE }
            ?.takeIf { it != Long.MIN_VALUE }
        return newestPointMs ?: nowMs
    }

    init {
        viewModelScope.launch {
            locationPreferences.mapProvider.collect { provider ->
                _uiState.update {
                    it.copy(showMapTiles = provider == LocationMapProvider.OpenStreetMap)
                }
            }
        }
        viewModelScope.launch {
            locationPreferences.allowLocationHistory.collect { enabled ->
                _uiState.update { it.copy(allowLocationHistory = enabled) }
            }
        }
        loadDay(_uiState.value.dayStartMs)
    }

    fun onAction(action: LocationHistoryUiAction) {
        when (action) {
            is LocationHistoryUiAction.SelectDay -> loadDay(localDayStart(action.dayStartMs))
            LocationHistoryUiAction.PreviousDay -> loadDay(shiftDay(_uiState.value.dayStartMs, -1))
            LocationHistoryUiAction.NextDay -> loadDay(shiftDay(_uiState.value.dayStartMs, 1))
            LocationHistoryUiAction.DeleteHistoryForDay -> deleteDay(_uiState.value.dayStartMs)
        }
    }

    private fun deleteDay(dayStartMs: Long) {
        if (peerDomain != null) return // read-only view of someone else's data
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            runCatching { deviceDirectory.deleteDayTraces(dayStartMs, shiftDay(dayStartMs, 1)) }
                .onFailure { logger.e(it) { "deleteDayTraces failed for $dayStartMs" } }
            loadDay(dayStartMs) // re-query → the deleted day now renders empty
        }
    }

    private fun loadDay(dayStartMs: Long) {
        _uiState.update { it.copy(dayStartMs = dayStartMs, isLoading = true) }
        viewModelScope.launch {
            val dayEndMs = shiftDay(dayStartMs, 1)
            val traces = runCatching {
                if (peerDomain != null) peerDayTraces(peerDomain, dayStartMs, dayEndMs)
                else deviceDirectory.loadDayTraces(dayStartMs, dayEndMs)
            }
                .onFailure { logger.e(it) { "loadDayTraces failed for $dayStartMs" } }
                .getOrDefault(emptyList())
            _uiState.update {
                // Drop stale results if the user already moved to another day.
                if (it.dayStartMs != dayStartMs) it
                else it.copy(traces = traces, isLoading = false)
            }
        }
    }

    /**
     * Peer mode day build: filter the retrieved hour-files' points to the day and gap-segment
     * per device via [LocationHistoryAssembler.singleDeviceTraces]. Pure in-memory transform.
     */
    private fun peerDayTraces(peer: String, dayStartMs: Long, dayEndMs: Long): List<DeviceTrace> {
        val result = emergencyLocateStore[peer] ?: return emptyList()
        return result.hours
            .groupBy { it.deviceId }
            .flatMap { (deviceId, hours) ->
                val dayPoints = hours
                    .flatMap { it.points }
                    .filter { it.t in dayStartMs until dayEndMs }
                LocationHistoryAssembler.singleDeviceTraces(dayPoints, deviceId)
            }
    }
}
