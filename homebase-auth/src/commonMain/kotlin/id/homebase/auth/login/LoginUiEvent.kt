package id.homebase.auth.login

sealed interface LoginUiEvent {
    data object NavigateToHome : LoginUiEvent
    data class ShowError(val message: String) : LoginUiEvent
    data class OpenUrl(val url: String) : LoginUiEvent
    data class OpenAuthUrl(val url: String) : LoginUiEvent
}