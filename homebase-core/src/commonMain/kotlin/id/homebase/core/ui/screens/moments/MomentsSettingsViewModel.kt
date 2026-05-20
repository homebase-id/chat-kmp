package id.homebase.core.ui.screens.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.moments.MomentsPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MomentsSettingsViewModel(
    private val momentsPreferences: MomentsPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MomentsSettingsUiState(
            iconVisible = momentsPreferences.iconVisible.value,
        )
    )
    val uiState: StateFlow<MomentsSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            momentsPreferences.iconVisible.collect { v ->
                _uiState.update { it.copy(iconVisible = v) }
            }
        }
    }

    fun onAction(action: MomentsSettingsUiAction) {
        when (action) {
            MomentsSettingsUiAction.OpenMomentsClicked -> {
                // Handled by the screen — it dispatches to the navigation callback.
            }
            is MomentsSettingsUiAction.SetIconVisible -> {
                viewModelScope.launch { momentsPreferences.setIconVisible(action.visible) }
            }
        }
    }
}
