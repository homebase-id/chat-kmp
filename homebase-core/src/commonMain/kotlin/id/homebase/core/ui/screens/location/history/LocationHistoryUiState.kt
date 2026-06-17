package id.homebase.core.ui.screens.location.history

data class LocationHistoryUiState(
    /** Device-local midnight of the shown day, epoch ms. */
    val dayStartMs: Long = 0L,
    val traces: List<DeviceTrace> = emptyList(),
    val stats: DayStats? = null,
    val isLoading: Boolean = true,
    val showMapTiles: Boolean = false,
    /** Time-warped playback model + dwell stops for the shown day; null while empty/loading. */
    val playback: DayPlayback? = null,
) {
    val isEmpty: Boolean get() = !isLoading && traces.isEmpty()
}

sealed interface LocationHistoryUiAction {
    data class SelectDay(val dayStartMs: Long) : LocationHistoryUiAction
    data object PreviousDay : LocationHistoryUiAction
    data object NextDay : LocationHistoryUiAction
}
