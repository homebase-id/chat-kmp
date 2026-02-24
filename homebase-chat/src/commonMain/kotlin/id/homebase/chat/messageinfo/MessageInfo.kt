package id.homebase.chat.messageinfo

sealed interface MessageInfo {
    data object BackClicked : MessageInfo
}