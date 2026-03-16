package id.homebase.chat.groupsettings

import androidx.compose.runtime.Immutable
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationUiModel
import kotlin.uuid.Uuid

@Immutable
data class GroupSettingsUiState(
    val isLoading: Boolean = true,
    val currentOdinId: OdinId? = null,
    val conversation: ConversationUiModel? = null,
    val isCurrentUserGroupAdmin: Boolean = false,
    val contacts: List<ContactUiModel> = listOf(),
    val uiEvent: GroupSettingsUiEvent? = null,
    val uiDialog: GroupSettingsUiDialog? = null,
    val uiSheet: GroupSettingsUiSheet? = null,
)

sealed interface GroupSettingsUiEvent {
    data object Back : GroupSettingsUiEvent
    data class Error(val errorMessage: String) : GroupSettingsUiEvent
    data class ShowContactInfo(val odinId: String) : GroupSettingsUiEvent
    data class ShowAddMembers(val conversationId: String) : GroupSettingsUiEvent
    data class ShowEditGroup(val conversationId: String) : GroupSettingsUiEvent
}

sealed interface GroupSettingsUiDialog {
    data object ConfirmLeave: GroupSettingsUiDialog
    data object LeaveChooseAdmin: GroupSettingsUiDialog
    data class MakeAdmin(val contact: ContactUiModel) : GroupSettingsUiDialog
    data class RemoveAdmin(val contact: ContactUiModel) : GroupSettingsUiDialog
    data class RemoveFromGroup(val contact: ContactUiModel) : GroupSettingsUiDialog
}

sealed interface GroupSettingsUiSheet {
    data class Member(val contactId: Uuid): GroupSettingsUiSheet
}


