package id.homebase.chat.conversationsettings

import androidx.compose.runtime.Immutable
import id.homebase.chat.data.ConversationUiModel

@Immutable
data class ConversationSettingsUiState(
    val isLoading: Boolean = true,
    val conversation: ConversationUiModel? = null,
    val uiEvent: ConversationSettingsUiEvent? = null,
)

sealed interface ConversationSettingsUiEvent {
    data object Back : ConversationSettingsUiEvent
    data class ShowContactInfo(val odinId: String) : ConversationSettingsUiEvent
}