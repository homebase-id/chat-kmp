package id.homebase.chat.groupsettings

import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel

sealed interface GroupSettingsUiAction {
    data object BackClicked : GroupSettingsUiAction
    data class ShowContactInfo(val contact: ContactUiModel) : GroupSettingsUiAction
    data class ShowMemberSheet(val contact: ContactUiModel) : GroupSettingsUiAction
    data object AddMembersClicked : GroupSettingsUiAction
    data object EditGroupClicked : GroupSettingsUiAction
    data object LeaveGroupClicked : GroupSettingsUiAction
    data object LeaveGroupConfirm : GroupSettingsUiAction
    data class MakeAdmin(val contact: ContactUiModel, val skipConfirmation: Boolean = false) : GroupSettingsUiAction
    data class RemoveAdmin(val contact: ContactUiModel, val skipConfirmation: Boolean = false) : GroupSettingsUiAction
    data class RemoveFromGroup(val contact: ContactUiModel, val skipConfirmation: Boolean = false) : GroupSettingsUiAction
    data class ConnectToIdentity(val odinId: OdinId) : GroupSettingsUiAction
    data object HealGroupClicked : GroupSettingsUiAction
}
