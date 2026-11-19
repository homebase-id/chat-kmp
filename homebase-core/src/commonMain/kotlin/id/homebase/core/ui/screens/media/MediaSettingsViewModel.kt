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
        viewModelScope.launch {
            userPreferences.preferenceState.collect { prefs ->
                _uiState.update { it.copy(mirrorFrontCamera = prefs.mirrorFrontCamera) }
            }
        }
    }

    fun onAction(action: MediaSettingsUiAction) {
        when (action) {
            is MediaSettingsUiAction.SetMirrorFrontCamera -> userPreferences.mirrorFrontCamera = action.enabled
        }
    }
}
