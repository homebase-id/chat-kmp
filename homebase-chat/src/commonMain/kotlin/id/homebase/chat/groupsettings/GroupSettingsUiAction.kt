package id.homebase.chat.groupsettings

import id.homebase.chat.data.ContactUiModel

sealed interface GroupSettingsUiAction {
    data object BackClicked : GroupSettingsUiAction
    data class ShowContactInfo(val contact: ContactUiModel) : GroupSettingsUiAction
    data object AddMembersClicked : GroupSettingsUiAction
    data object EditGroupClicked : GroupSettingsUiAction
}
