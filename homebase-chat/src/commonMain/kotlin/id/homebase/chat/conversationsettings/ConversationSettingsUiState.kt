package id.homebase.chat.conversationsettings

import androidx.compose.runtime.Immutable
import id.homebase.api.client.auth.OwnerSession
import id.homebase.chat.data.ConversationUiModel

@Immutable
data class ConversationSettingsUiState(
    val isLoading: Boolean = true,
    val conversation: ConversationUiModel? = null,
    val ownerSession: OwnerSession? = null,
    val isSummaryLoading: Boolean = true,
    val summary: ChatSummaryUiModel? = null,
    val uiEvent: ConversationSettingsUiEvent? = null,
)

sealed interface ConversationSettingsUiEvent {
    data object Back : ConversationSettingsUiEvent
    data class ShowContactInfo(val odinId: String) : ConversationSettingsUiEvent
}