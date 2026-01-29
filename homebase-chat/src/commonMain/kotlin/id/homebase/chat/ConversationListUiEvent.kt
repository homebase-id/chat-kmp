package id.homebase.chat

sealed interface ConversationListUiEvent {
    data object NavigateBack : ConversationListUiEvent
}