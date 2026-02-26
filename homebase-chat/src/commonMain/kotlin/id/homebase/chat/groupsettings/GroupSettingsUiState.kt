package id.homebase.chat.groupsettings

import androidx.compose.runtime.Immutable
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationUiModel

@Immutable
data class GroupSettingsUiState(
    val isLoading: Boolean = true,
    val currentOdinId: String = "",
    val conversation: ConversationUiModel? = null,
    val contacts: List<ContactUiModel> = listOf(),
    val uiEvent: GroupSettingsUiEvent? = null,
)

sealed interface GroupSettingsUiEvent {
    data object Back : GroupSettingsUiEvent
    data class ShowContactInfo(val odinId: String) : GroupSettingsUiEvent
    data class ShowAddMembers(val conversationId: String) : GroupSettingsUiEvent
    data class ShowEditGroup(val conversationId: String) : GroupSettingsUiEvent
}
