package id.homebase.core.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.settings.PreferenceState
import id.homebase.core.settings.SendQualityApp
import id.homebase.core.settings.UserPreferences
import id.homebase.core.settings.sendQuality
import id.homebase.core.util.AlbumSaver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatSettingsViewModel(
    private val userPreferences: UserPreferences,
    albumSaver: AlbumSaver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatSettingsUiState(savesInBackground = albumSaver.savesUnasked).with(userPreferences.preferenceState.value),
    )
    val uiState: StateFlow<ChatSettingsUiState> = _uiState.asStateFlow()

    init {
        // The composer's quality chip writes the same preference.
        viewModelScope.launch {
            userPreferences.preferenceState.collect { prefs -> _uiState.update { it.with(prefs) } }
        }
    }

    fun onAction(action: ChatSettingsUiAction) {
        when (action) {
            is ChatSettingsUiAction.SetQuality -> userPreferences.setSendQuality(SendQualityApp.Chat, action.quality)
            is ChatSettingsUiAction.SetAutoSaveIncomingMedia -> userPreferences.autoSaveIncomingMedia = action.enabled
            is ChatSettingsUiAction.SetAutoSaveOnUnmeteredOnly -> userPreferences.autoSaveOnUnmeteredOnly = action.enabled
        }
    }

    private fun ChatSettingsUiState.with(prefs: PreferenceState) = copy(
        quality = prefs.sendQuality(SendQualityApp.Chat),
        autoSaveIncomingMedia = prefs.autoSaveIncomingMedia,
        autoSaveOnUnmeteredOnly = prefs.autoSaveOnUnmeteredOnly,
    )
}
