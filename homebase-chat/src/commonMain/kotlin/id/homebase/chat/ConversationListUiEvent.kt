package id.homebase.chat

sealed interface ConversationListUiEvent {
    data object NavigateBack : ConversationListUiEvent
    data class ShowErrorMessage(val message: String) : ConversationListUiEvent

    data class OpenUrl(val url: String) : ConversationListUiEvent

}