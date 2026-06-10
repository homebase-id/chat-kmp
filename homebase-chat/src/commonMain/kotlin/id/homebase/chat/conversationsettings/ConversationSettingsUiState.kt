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
    /**
     * Resolved full name of the other party in a 1:1 conversation (from the
     * contact list). Null for groups, note-to-self, or when the contact has no
     * display name — [ConversationUiModel.name] (the raw odinId) is the fallback.
     */
    val contactName: String? = null,
    val ownerSession: OwnerSession? = null,
    val isOverviewLoading: Boolean = true,
    val overview: ConversationOverview? = null,
    val groupsInCommon: ImmutableList<GroupInCommonItem> = persistentListOf(),
    val uiEvent: ConversationSettingsUiEvent? = null,
)

sealed interface ConversationSettingsUiEvent {
    data object Back : ConversationSettingsUiEvent
}
