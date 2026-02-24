package id.homebase.chat.conversationlist

import id.homebase.chat.data.MessageUiModel

sealed interface ConversationListUiEvent {
    data object NavigateBack : ConversationListUiEvent
    data object NavigateToNewConversation : ConversationListUiEvent
    data class NavigateToContactInfo(val odinId: String) : ConversationListUiEvent
    data class NavigateToMessageInfo(val message: MessageUiModel) : ConversationListUiEvent
    data class ShowErrorMessage(val message: String) : ConversationListUiEvent
}