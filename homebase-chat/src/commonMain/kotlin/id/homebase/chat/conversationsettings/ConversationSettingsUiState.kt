package id.homebase.chat.conversationsettings

import androidx.compose.runtime.Immutable

@Immutable
data class ConversationSettingsUiState(
    val text: String,

    val uiEvent: ConversationSettingsUiEvent? = null,
)

sealed interface ConversationSettingsUiEvent {
    object Back : ConversationSettingsUiEvent
}
