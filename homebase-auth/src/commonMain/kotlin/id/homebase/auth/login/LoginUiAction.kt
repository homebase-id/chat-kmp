package id.homebase.auth.login

sealed interface LoginUiAction {

    data object CreateAccount : LoginUiAction

    data class LoginClicked(val homebaseId: String) : LoginUiAction

    /**
     * App returned to foreground (e.g. browser auth cancelled or completed)
     */
    data object AppResumed : LoginUiAction
}

