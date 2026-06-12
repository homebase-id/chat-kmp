package id.homebase.core.ui.screens.location.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.core.location.LocationPreferences
import id.homebase.core.ui.screens.location.history.DeviceTrace
import id.homebase.core.ui.screens.location.history.localDayStart
import id.homebase.core.ui.screens.location.history.shiftDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.uuid.Uuid

data class FindDeviceUiState(
    /** null = picker (device list); set = locating that device. */
    val deviceId: Uuid? = null,
    val devices: List<LocationDeviceInfo> = emptyList(),
    val device: LocationDeviceInfo? = null,
    /** The selected device's trace for today (its last point is the position). */
    val deviceTrace: DeviceTrace? = null,
    val isLoading: Boolean = true,
    val showMapTiles: Boolean = false,
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
            showMapTiles = locationPreferences.showMapTiles.value,
        )
    )
    val uiState: StateFlow<FindDeviceUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val devices = runCatching { deviceDirectory.loadDevices() }
                .onFailure { logger.e(it) { "loadDevices failed" } }
                .getOrDefault(emptyList())
            val device = deviceIdArg?.let { id -> devices.firstOrNull { it.deviceId == id } }
            val trace = if (deviceIdArg != null) {
                val dayStart = localDayStart(Clock.System.now().toEpochMilliseconds())
                runCatching { deviceDirectory.loadDayTraces(dayStart, shiftDay(dayStart, 1)) }
                    .getOrDefault(emptyList())
                    .firstOrNull { it.deviceId == deviceIdArg }
            } else null
            _uiState.update {
                it.copy(devices = devices, device = device, deviceTrace = trace, isLoading = false)
            }
        }
    }
}
