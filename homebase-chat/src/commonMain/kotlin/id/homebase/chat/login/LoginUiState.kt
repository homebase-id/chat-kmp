package id.homebase.chat.login

data class LoginUiState(
    val homebaseId: String = "",
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val errorMessage: String? = null,
    val uiEvent: LoginUiEvent? = null
)
