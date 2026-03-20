package id.homebase.chat.createconversation

import id.homebase.chat.data.ContactUiModel

sealed interface CreateConversationUiAction {
    data object BackClicked : CreateConversationUiAction
    data object CreateNewGroup : CreateConversationUiAction
    data object CreateSelfConversation : CreateConversationUiAction
    data class ContactClicked(val contact: ContactUiModel): CreateConversationUiAction
}