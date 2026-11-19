package id.homebase.core.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.image.SendQuality
import id.homebase.core.settings.SendQualityApp
import id.homebase.core.settings.UserPreferences
import id.homebase.core.settings.sendQuality
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ChatSettingsUiState(
    val quality: SendQuality = SendQualityApp.Chat.default,
    val autoSaveIncomingMedia: Boolean = false,
    val autoSaveOnUnmeteredOnly: Boolean = true,
)

sealed interface ChatSettingsUiAction {
    data class SetQuality(val quality: SendQuality) : ChatSettingsUiAction
    data class SetAutoSaveIncomingMedia(val enabled: Boolean) : ChatSettingsUiAction
    data class SetAutoSaveOnUnmeteredOnly(val enabled: Boolean) : ChatSettingsUiAction
}

class ChatSettingsViewModel(private val userPreferences: UserPreferences) : ViewModel() {

    // The composer's quality chip writes the same preference, so follow the mirrored flow.
    val uiState: StateFlow<ChatSettingsUiState> = userPreferences.preferenceState
        .map { prefs ->
            ChatSettingsUiState(
                quality = prefs.sendQuality(SendQualityApp.Chat),
                autoSaveIncomingMedia = prefs.autoSaveIncomingMedia,
                autoSaveOnUnmeteredOnly = prefs.autoSaveOnUnmeteredOnly,
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatSettingsUiState())

    fun onAction(action: ChatSettingsUiAction) {
        when (action) {
            is ChatSettingsUiAction.SetQuality -> userPreferences.setSendQuality(SendQualityApp.Chat, action.quality)
            is ChatSettingsUiAction.SetAutoSaveIncomingMedia -> userPreferences.autoSaveIncomingMedia = action.enabled
            is ChatSettingsUiAction.SetAutoSaveOnUnmeteredOnly -> userPreferences.autoSaveOnUnmeteredOnly = action.enabled
        }
    }
}
