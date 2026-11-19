package id.homebase.core.ui.screens.moments

import id.homebase.core.settings.SendQualityApp
import id.homebase.core.settings.UserPreferences
import id.homebase.core.settings.sendQuality
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
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MomentsSettingsUiState(
            iconVisible = momentsPreferences.iconVisible.value,
            quality = userPreferences.sendQuality(SendQualityApp.Moments),
            autoSaveIncomingMedia = userPreferences.momentsAutoSaveIncomingMedia,
        )
    )
    val uiState: StateFlow<MomentsSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            momentsPreferences.iconVisible.collect { v ->
                _uiState.update { it.copy(iconVisible = v) }
            }
        }
        // The composer's quality chip writes the same preference.
        viewModelScope.launch {
            userPreferences.preferenceState.collect { prefs ->
                _uiState.update { it.copy(quality = prefs.sendQuality(SendQualityApp.Moments)) }
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
            is MomentsSettingsUiAction.SetQuality -> userPreferences.setSendQuality(SendQualityApp.Moments, action.quality)
            is MomentsSettingsUiAction.SetAutoSaveIncomingMedia -> {
                userPreferences.momentsAutoSaveIncomingMedia = action.enabled
                _uiState.update { it.copy(autoSaveIncomingMedia = action.enabled) }
            }
        }
    }
}
