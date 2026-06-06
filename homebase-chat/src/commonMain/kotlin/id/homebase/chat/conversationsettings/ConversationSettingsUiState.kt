package id.homebase.chat.conversationsettings

import androidx.compose.runtime.Immutable
import id.homebase.api.client.auth.OwnerSession
import id.homebase.chat.data.ConversationUiModel

@Immutable
data class ConversationSettingsUiState(
    val isLoading: Boolean = true,
    val conversation: ConversationUiModel? = null,
    val ownerSession: OwnerSession? = null,
    val uiEvent: ConversationSettingsUiEvent? = null,
)

sealed interface ConversationSettingsUiEvent {
    data object Back : ConversationSettingsUiEvent
    data class ShowContactInfo(val odinId: String, val conversationId: String) : ConversationSettingsUiEvent
}