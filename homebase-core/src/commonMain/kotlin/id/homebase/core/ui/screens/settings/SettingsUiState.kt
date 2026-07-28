package id.homebase.core.ui.screens.settings

import id.homebase.api.client.auth.OwnerSession

enum class NotificationVerificationStatus { CHECKING, OK, ERROR }

data class SettingsUiState(
    val isLoading: Boolean = false,
    val isLoggingOut: Boolean = false,
    val ownerSession: OwnerSession? = null,
    val notificationStatus: NotificationVerificationStatus = NotificationVerificationStatus.CHECKING,
    val useNativeFeed: Boolean = true,

    val uiEvent: SettingsUiEvent? = null,
    val uiDialog: SettingsUiDialog? = null,
)

/** All possible user actions on Settings screen. */
sealed interface SettingsUiAction {
    data object LogoutClicked : SettingsUiAction
    data object DeleteAccount: SettingsUiAction
    data object OpenOwnerConsoleClicked : SettingsUiAction
    data object ProfileInfoClicked : SettingsUiAction
    data object SecuritySetupClicked : SettingsUiAction
    data class SetUseNativeFeed(val enabled: Boolean) : SettingsUiAction
    data object AvatarClicked : SettingsUiAction
}

/** One-off events for side effects (navigation). */
sealed interface SettingsUiEvent {
    data object LoggedOut : SettingsUiEvent
    data class OpenUrl(val url: String): SettingsUiEvent
    /** Open the in-app standard-profile editor. */
    data object NavigateToProfileEdit : SettingsUiEvent
    /** Open the dedicated avatar pick/crop/upload screen. */
    data object NavigateToProfileAvatarEdit : SettingsUiEvent
}

sealed interface SettingsUiDialog {
    data object DeleteAccount: SettingsUiDialog
}
