package id.homebase.auth.login

import id.homebase.api.common.OdinId

sealed interface LoginUiAction {

    data class LoginClicked(val homebaseId: OdinId) : LoginUiAction

    data class RetryClicked(val homebaseId: OdinId)  : LoginUiAction

    /**
     * App returned to foreground (e.g. browser auth cancelled or completed)
     */
    data object AppResumed : LoginUiAction
}

