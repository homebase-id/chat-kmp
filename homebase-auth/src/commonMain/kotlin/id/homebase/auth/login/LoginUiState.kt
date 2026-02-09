package id.homebase.auth.login

import id.homebase.api.common.OdinId

data class LoginUiState(
    val homebaseId: OdinId? = null,
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val errorMessage: String? = null,
    val uiEvent: LoginUiEvent? = null
)
