package id.homebase.core.ui.screens.settings

import id.homebase.api.image.SendQuality
import id.homebase.core.settings.SendQualityApp

data class ChatSettingsUiState(
    val quality: SendQuality = SendQualityApp.Chat.default,
    val autoSaveIncomingMedia: Boolean = false,
    val autoSaveOnUnmeteredOnly: Boolean = true,
    /** False on the web, whose album only takes what a person saves. */
    val savesInBackground: Boolean = true,
)

sealed interface ChatSettingsUiAction {
    data class SetQuality(val quality: SendQuality) : ChatSettingsUiAction
    data class SetAutoSaveIncomingMedia(val enabled: Boolean) : ChatSettingsUiAction
    data class SetAutoSaveOnUnmeteredOnly(val enabled: Boolean) : ChatSettingsUiAction
}
