package id.homebase.chat.messageinfo

sealed interface MessageInfoUiAction {
    data object BackClicked : MessageInfoUiAction
    data object RetryClicked : MessageInfoUiAction
}