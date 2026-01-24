package id.homebase.chat.login

sealed interface LoginUiEvent {
    data object NavigateToHome : LoginUiEvent
    data class ShowError(val message: String) : LoginUiEvent
}