package id.homebase.chat.contactinfo

sealed interface ContactInfoUiAction {
    data object BackClicked : ContactInfoUiAction
}