package id.homebase.chat.conversationsettings

import id.homebase.api.common.OdinId

sealed interface ConversationSettingsUiAction {
    data object BackClicked : ConversationSettingsUiAction
    data class ShowContactInfo(val odinId: OdinId) : ConversationSettingsUiAction
}