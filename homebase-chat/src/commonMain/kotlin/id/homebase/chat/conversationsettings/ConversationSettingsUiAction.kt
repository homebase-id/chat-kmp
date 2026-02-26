package id.homebase.chat.conversationsettings

import id.homebase.chat.data.ContactUiModel

sealed interface ConversationSettingsUiAction {
    data object BackClicked : ConversationSettingsUiAction
    data class ShowContactInfo(val contact: ContactUiModel) : ConversationSettingsUiAction
}