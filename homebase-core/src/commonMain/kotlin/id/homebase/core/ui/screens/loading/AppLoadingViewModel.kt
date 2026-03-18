package id.homebase.core.ui.screens.loading

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.util.StartupState
import id.homebase.core.util.mapToStartupState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppLoadingViewModel(
    private val youAuthFlowManager: YouAuthFlowManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppLoadingUiState())
    val uiState: StateFlow<AppLoadingUiState> = _uiState.asStateFlow()

    init {
        // We should wait here until restoring credentials or initial sync is done, then redirect to main screen
        // If no credentials are found, redirect to the login screen
        viewModelScope.launch {
            youAuthFlowManager.authState.collectLatest { youAuthState ->
                val authState = youAuthState.mapToStartupState(false)
                Logger.i(tag = "AppLoadingViewModel", messageString = "AuthState: $authState")
                if (authState is StartupState.Authenticated) {
                    _uiState.update { it.copy(uiEvent = AppLoadingUiEvent.NavigateToMainScreen) }
                } else if (authState is StartupState.Unauthenticated) {
                    _uiState.update { it.copy(uiEvent = AppLoadingUiEvent.NavigateToLogin) }
                }
            }
        }
    }

    fun onUiAction(action: AppLoadingUiAction) {
        when (action) {
            is AppLoadingUiAction.Retry -> {
                //_uiState.update { it.copy(uiEvent = AppLoadingUiEvent.Back) }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }
}

@Immutable
data class AppLoadingUiState(
    val uiEvent: AppLoadingUiEvent? = null,
)

sealed interface AppLoadingUiEvent {
    data object NavigateToMainScreen : AppLoadingUiEvent
    data object NavigateToLogin : AppLoadingUiEvent
    data class Error(val errorMessage: String) : AppLoadingUiEvent
}

sealed interface AppLoadingUiAction {
    data object Retry : AppLoadingUiAction
}
