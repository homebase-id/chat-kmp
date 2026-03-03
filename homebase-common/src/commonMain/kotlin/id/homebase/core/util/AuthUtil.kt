package id.homebase.core.util

import androidx.compose.runtime.Immutable
import id.homebase.api.youauth.YouAuthState

@Immutable
sealed interface StartupState {
    data object Loading : StartupState
    data class Error(val message: String) : StartupState
    data object Authenticated : StartupState
    data object Authenticating : StartupState
    data object Unauthenticated : StartupState
}

fun YouAuthState.mapToStartupState(isDoingInitialConnection: Boolean): StartupState {
    return when (this) {
        is YouAuthState.Authenticated ->  if (isDoingInitialConnection) StartupState.Loading else StartupState.Authenticated
        is YouAuthState.Error -> StartupState.Error(this.message)
        YouAuthState.Authenticating -> StartupState.Authenticating
        YouAuthState.Initializing -> StartupState.Loading
        YouAuthState.Unauthenticated -> StartupState.Unauthenticated
    }
}