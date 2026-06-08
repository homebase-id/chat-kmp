package id.homebase.chat.conversationsettings

import androidx.compose.runtime.Immutable
import id.homebase.api.client.auth.OwnerSession
import id.homebase.chat.data.ConversationUiModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class ConversationSettingsUiState(
    val isLoading: Boolean = true,
    val conversation: ConversationUiModel? = null,
    val ownerSession: OwnerSession? = null,
    val isOverviewLoading: Boolean = true,
    val overview: ConversationOverview? = null,
    val groupsInCommon: ImmutableList<GroupInCommonItem> = persistentListOf(),
    val uiEvent: ConversationSettingsUiEvent? = null,
)

sealed interface ConversationSettingsUiEvent {
    data object Back : ConversationSettingsUiEvent
}
