package id.homebase.core.ui.screens.location.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.location.LocationPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LocationSettingsViewModel(
    private val locationPreferences: LocationPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        LocationSettingsUiState(
            iconVisible = locationPreferences.iconVisible.value,
        )
    )
    val uiState: StateFlow<LocationSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            locationPreferences.iconVisible.collect { v ->
                _uiState.update { it.copy(iconVisible = v) }
            }
        }
    }

    fun onAction(action: LocationSettingsUiAction) {
        when (action) {
            LocationSettingsUiAction.OpenLocationClicked -> {
                // Handled by the screen — it dispatches to the navigation callback.
            }
            is LocationSettingsUiAction.SetIconVisible -> {
                viewModelScope.launch { locationPreferences.setIconVisible(action.visible) }
            }
        }
    }
}
