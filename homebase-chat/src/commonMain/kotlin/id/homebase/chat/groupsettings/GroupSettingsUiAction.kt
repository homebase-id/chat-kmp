package id.homebase.chat.groupsettings

sealed interface GroupSettingsUiAction {
    data object BackClicked : GroupSettingsUiAction
}