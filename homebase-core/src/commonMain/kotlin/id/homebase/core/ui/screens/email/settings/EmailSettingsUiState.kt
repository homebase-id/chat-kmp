package id.homebase.core.ui.screens.email.settings

import id.homebase.api.client.mail.MailboxMode

data class EmailSettingsUiState(
    val iconVisible: Boolean = true,
    val biometricsEnabled: Boolean = true,
    /** Null until a mailbox exists on a server that supports the choice: nothing to switch yet. */
    val mailboxMode: MailboxMode? = null,
)

sealed interface EmailSettingsUiAction {
    data object OpenEmailClicked : EmailSettingsUiAction
    data object ChangeModeClicked : EmailSettingsUiAction
    data object ScreenShown : EmailSettingsUiAction
    data class SetIconVisible(val visible: Boolean) : EmailSettingsUiAction
    data class SetBiometricsEnabled(val enabled: Boolean) : EmailSettingsUiAction
}
