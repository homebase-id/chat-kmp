package id.homebase.core.ui.screens.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.settings.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MediaSettingsViewModel(
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediaSettingsUiState())
    val uiState: StateFlow<MediaSettingsUiState> = _uiState.asStateFlow()

    init {
        // The composer's HD chip writes the same preference, so follow the mirrored flow.
        viewModelScope.launch {
            userPreferences.preferenceState.collect { prefs ->
                _uiState.update {
                    it.copy(
                        mediaQuality = prefs.mediaQuality,
                        autoSaveIncomingMedia = prefs.autoSaveIncomingMedia,
                        autoSaveOnUnmeteredOnly = prefs.autoSaveOnUnmeteredOnly,
                    )
                }
            }
        }
    }

    fun onAction(action: MediaSettingsUiAction) {
        when (action) {
            is MediaSettingsUiAction.SetMediaQuality -> {
                userPreferences.mediaQuality = action.quality
            }

            is MediaSettingsUiAction.SetAutoSaveIncomingMedia -> {
                userPreferences.autoSaveIncomingMedia = action.enabled
            }

            is MediaSettingsUiAction.SetAutoSaveOnUnmeteredOnly -> {
                userPreferences.autoSaveOnUnmeteredOnly = action.enabled
            }
        }
    }
}
