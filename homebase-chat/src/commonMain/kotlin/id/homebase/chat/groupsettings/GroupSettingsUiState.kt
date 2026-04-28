package id.homebase.chat.groupsettings

import androidx.compose.runtime.Immutable
import id.homebase.api.client.drives.files.TransferStatus
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationUiModel
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.StringResource

@Immutable
data class GroupSettingsUiState(
    val isLoading: Boolean = true,
    val currentOdinId: OdinId? = null,
    val conversation: ConversationUiModel? = null,
    val isCurrentUserGroupAdmin: Boolean = false,
    val isLegacyGroup: Boolean = false,
    val contacts: List<ContactUiModel> = listOf(),
    /** Per-recipient transfer state for the main conversation file. null = caller is not
     *  the original author and the column should be hidden. */
    val mainFileTransfer: Map<OdinId, RecipientFileStatus>? = null,
    /** Per-recipient transfer state for the admin file. null = column hidden (see above). */
    val adminFileTransfer: Map<OdinId, RecipientFileStatus>? = null,
    val isHealing: Boolean = false,
    val uiEvent: GroupSettingsUiEvent? = null,
    val uiDialog: GroupSettingsUiDialog? = null,
    val uiSheet: GroupSettingsUiSheet? = null,
) {
    val canHeal: Boolean get() = mainFileTransfer != null || adminFileTransfer != null
}

@Immutable
sealed interface RecipientFileStatus {
    data object Ok : RecipientFileStatus
    data class Problem(val rawStatus: TransferStatus, val detailRes: StringResource?) : RecipientFileStatus
}

sealed interface GroupSettingsUiEvent {
    data object Back : GroupSettingsUiEvent
    data class Error(val errorMessage: String) : GroupSettingsUiEvent
    data class ShowContactInfo(val odinId: String) : GroupSettingsUiEvent
    data class ShowAddMembers(val conversationId: String) : GroupSettingsUiEvent
    data class ShowEditGroup(val conversationId: String) : GroupSettingsUiEvent
    data class OpenUrl(val url: String) : GroupSettingsUiEvent
    data class HealCompleted(val mainHealed: Boolean, val adminHealed: Boolean) : GroupSettingsUiEvent
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


