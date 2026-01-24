package id.homebase.chat.login

sealed interface LoginUiAction {

    data class HomebaseIdChanged(val value: String) : LoginUiAction

    data object LoginClicked : LoginUiAction

    data object RetryClicked : LoginUiAction

    /**
     * App returned to foreground (e.g. browser auth cancelled or completed)
     */
    data object AppResumed : LoginUiAction
}

