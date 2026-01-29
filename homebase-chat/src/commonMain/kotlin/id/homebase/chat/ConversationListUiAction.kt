package id.homebase.chat

import id.homebase.chat.data.ContactUiModel
import kotlin.uuid.Uuid

sealed interface ConversationListUiAction {
    data class ConversationClicked(val conversationId: Uuid) : ConversationListUiAction
    data object BackClicked : ConversationListUiAction
    data object NewChatClicked : ConversationListUiAction
    data object BackToListClicked : ConversationListUiAction
    data class ContactClicked(val contact: ContactUiModel) : ConversationListUiAction
    data class SearchQueryChanged(val query: String) : ConversationListUiAction
    data class SendMessage(val conversationId: Uuid, val content: String) : ConversationListUiAction

    data class SaveScrollPosition(
        val conversationId: Uuid,
        val firstVisibleItemIndex: Int,
        val firstVisibleItemScrollOffset: Int
    ) : ConversationListUiAction
}