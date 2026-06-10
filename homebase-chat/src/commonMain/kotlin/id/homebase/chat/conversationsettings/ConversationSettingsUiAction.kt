package id.homebase.chat.conversationsettings

sealed interface ConversationSettingsUiAction {
    data object BackClicked : ConversationSettingsUiAction
}