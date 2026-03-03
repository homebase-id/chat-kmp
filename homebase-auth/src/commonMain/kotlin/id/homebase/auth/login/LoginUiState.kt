package id.homebase.auth.login

data class LoginUiState(
    val homebaseId: String = "",
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val isDoingInitialConnection: Boolean = false,
    val errorMessage: String? = null,
    val uiEvent: LoginUiEvent? = null
)
