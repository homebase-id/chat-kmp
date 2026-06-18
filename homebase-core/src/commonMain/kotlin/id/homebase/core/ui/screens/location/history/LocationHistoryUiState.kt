package id.homebase.core.ui.screens.location.history

data class LocationHistoryUiState(
    /** Device-local midnight of the shown day, epoch ms. */
    val dayStartMs: Long = 0L,
    val traces: List<DeviceTrace> = emptyList(),
    val isLoading: Boolean = true,
    val showMapTiles: Boolean = false,
)

sealed interface LocationHistoryUiAction {
    data class SelectDay(val dayStartMs: Long) : LocationHistoryUiAction
    data object PreviousDay : LocationHistoryUiAction
    data object NextDay : LocationHistoryUiAction
}
