package id.homebase.chat.newconversation

import id.homebase.api.common.OdinId

sealed interface NewConversationUiAction {
    data object BackClicked : NewConversationUiAction
    data object CreateNewGroup : NewConversationUiAction
    data class CreateConversation(val odinId: OdinId): NewConversationUiAction
}