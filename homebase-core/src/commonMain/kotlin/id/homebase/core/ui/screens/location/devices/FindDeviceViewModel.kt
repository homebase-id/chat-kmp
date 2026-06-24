package id.homebase.core.ui.screens.location.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.core.location.LocationMapProvider
import id.homebase.core.location.LocationPreferences
import id.homebase.core.ui.screens.location.history.DeviceTrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

data class FindDeviceUiState(
    /** null = picker (device list); set = locating that device. */
    val deviceId: Uuid? = null,
    val devices: List<LocationDeviceInfo> = emptyList(),
    val device: LocationDeviceInfo? = null,
    /** A single-point trace at the device's last known position (the "dot"). */
    val deviceTrace: DeviceTrace? = null,
    val isLoading: Boolean = true,
    val showMapTiles: Boolean = false,
    /** Whether this device records location history — drives the empty-state "turn it on" hint. */
    val allowLocationHistory: Boolean = false,
)

class FindDeviceViewModel(
    private val deviceIdArg: Uuid?,
    private val deviceDirectory: LocationDeviceDirectory,
    locationPreferences: LocationPreferences,
) : ViewModel() {

    private val logger = Logger.withTag("FindDeviceViewModel")

    private val _uiState = MutableStateFlow(
        FindDeviceUiState(
            deviceId = deviceIdArg,
            showMapTiles =
                locationPreferences.mapProvider.value == LocationMapProvider.OpenStreetMap,
            allowLocationHistory = locationPreferences.allowLocationHistory.value,
        )
    )
    val uiState: StateFlow<FindDeviceUiState> = _uiState.asStateFlow()

    init {
        refresh()
        // Reactive so the empty-state hint clears once the user enables history and returns.
        viewModelScope.launch {
            locationPreferences.allowLocationHistory.collect { enabled ->
                _uiState.update { it.copy(allowLocationHistory = enabled) }
            }
        }
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val devices = runCatching { deviceDirectory.loadDevices() }
                .onFailure { logger.e(it) { "loadDevices failed" } }
                .getOrDefault(emptyList())
            val device = deviceIdArg?.let { id -> devices.firstOrNull { it.deviceId == id } }
            // Just the last known position as one point — "find my device", not a
            // day's trail. loadDevices() already resolved each device's freshest fix.
            val trace = device?.lastFix?.let { fix ->
                DeviceTrace(deviceId = device.deviceId, segments = listOf(listOf(fix)))
            }
            _uiState.update {
                it.copy(devices = devices, device = device, deviceTrace = trace, isLoading = false)
            }
        }
    }
}
