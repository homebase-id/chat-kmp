package id.homebase.chat.newconversation

sealed interface NewConversationUiAction {
    data object BackClicked : NewConversationUiAction
}