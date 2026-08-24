package id.homebase.chat.createconversation

import id.homebase.chat.data.ContactUiModel
import kotlin.uuid.Uuid

sealed interface CreateConversationUiAction {
    data object BackClicked : CreateConversationUiAction
    data object RefreshClicked : CreateConversationUiAction
    data object CreateNewGroup : CreateConversationUiAction
    data object CreateSelfConversation : CreateConversationUiAction
    data class ContactClicked(val contact: ContactUiModel): CreateConversationUiAction
    data class ExistingConversationClicked(val conversationId: Uuid) : CreateConversationUiAction
}