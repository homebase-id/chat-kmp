package id.homebase.auth.login

import androidx.compose.runtime.Immutable

@Immutable
data class LoginUiState(
    val homebaseId: String = "",
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val errorMessage: String? = null,
    val progress: LoginProgress? = null,
    val uiEvent: LoginUiEvent? = null
)

@Immutable
data class LoginProgress(
    val driveId: String,
    val error: String? = null,
    val completed: Boolean = false,
    val progress: Float? = null,
    val count: Int = 0,
    val total: Int = 0,
)
