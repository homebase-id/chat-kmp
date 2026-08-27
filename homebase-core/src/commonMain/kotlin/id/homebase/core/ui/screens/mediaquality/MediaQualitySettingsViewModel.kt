package id.homebase.core.ui.screens.mediaquality

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.settings.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MediaQualitySettingsViewModel(
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediaQualitySettingsUiState())
    val uiState: StateFlow<MediaQualitySettingsUiState> = _uiState.asStateFlow()

    init {
        // The composer's HD chip writes the same preference, so follow the mirrored flow rather
        // than a one-shot read.
        viewModelScope.launch {
            userPreferences.preferenceState.collect { prefs ->
                _uiState.update { it.copy(mediaQuality = prefs.mediaQuality) }
            }
        }
    }

    fun onAction(action: MediaQualitySettingsUiAction) {
        when (action) {
            is MediaQualitySettingsUiAction.SetMediaQuality -> {
                userPreferences.mediaQuality = action.quality
            }
        }
    }
}
