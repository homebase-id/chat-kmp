package id.homebase.chat.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LoginViewModel(
    private val youAuthFlowManager: YouAuthFlowManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState

    init {
        observeAuthState()
    }

    fun onAction(action: LoginUiAction) {
        when (action) {
            is LoginUiAction.HomebaseIdChanged -> {
                _uiState.update {
                    it.copy(
                        homebaseId = action.value,
                        errorMessage = null
                    )
                }
            }

            LoginUiAction.LoginClicked -> {
                startLogin()
            }

            LoginUiAction.RetryClicked -> {
                _uiState.update {
                    it.copy(
                        errorMessage = null,
                        isLoading = false
                    )
                }
            }

            LoginUiAction.AppResumed -> {
                // Auth flow may have completed or been cancelled
                observeAuthState()
            }
        }
    }

    /* ---------------- PRIVATE ---------------- */

    private fun startLogin() {
        val homebaseId = _uiState.value.homebaseId.trim()

        if (homebaseId.isEmpty()) {
            _uiState.update {
                it.copy(errorMessage = "Homebase ID is required")
            }
            return
        }

        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            youAuthFlowManager.startAuthentication(homebaseId)
        }
    }

    private fun observeAuthState() {
        viewModelScope.launch {
            youAuthFlowManager.authState.collect { authState ->
                when (authState) {
                    is YouAuthState.Authenticated -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isAuthenticated = true,
                                errorMessage = null
                            )
                        }
                    }

                    is YouAuthState.Unauthenticated -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isAuthenticated = false
                            )
                        }
                    }

                    is YouAuthState.Authenticating -> {
                        _uiState.update {
                            it.copy(isLoading = true)
                        }
                    }

                    is YouAuthState.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isAuthenticated = false,
                                errorMessage = authState.message ?: "Authentication failed"
                            )
                        }
                    }
                }
            }
        }
    }
}
