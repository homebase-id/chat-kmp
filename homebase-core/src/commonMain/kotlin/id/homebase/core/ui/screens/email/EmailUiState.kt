package id.homebase.core.ui.screens.email

import id.homebase.api.client.mail.MailAppStatus
import id.homebase.api.client.mail.MailboxStatusResult
import id.homebase.core.email.MailClientDescriptor

/**
 * What the Email setup screen renders from.
 *
 * Two independent facts decide the screen, and both can be unknown at first paint:
 *  - [serverStatus] — does this host run email at all, and how far has setup got. Server-owned.
 *  - [driveActivated] — is the email drive mounted on this device. Drive-owned.
 *
 * Neither is a local flag, deliberately: a device-local "activated" boolean diverges across a
 * user's devices (ADDING_ADDON_APPS.md), and setup progress that lives only on one device cannot
 * survive the app being killed.
 */
data class EmailUiState(
    /** null while the first status call is still in flight. */
    val serverStatus: MailAppStatus? = null,
    val isCheckingServer: Boolean = false,
    val statusError: EmailError? = null,
    /** null while the drive mount state is still resolving. */
    val driveActivated: Boolean? = null,
    /** Credential files on the drive — the last setup step's signal. */
    val credentialCount: Int = 0,
    /** null until email is on and the mail server has answered. */
    val mailboxStatus: MailboxStatusResult? = null,
    /** The mail app the user picked, if any — decides whether we can offer to open it. */
    val selectedMailClient: MailClientDescriptor? = null,
) {
    /** The server answered, and answered no. Nothing to set up here. */
    val serverHasNoEmail: Boolean
        get() = serverStatus?.tenantMailEnabled == false

    /** Waiting on the first answer — show a spinner rather than guessing. */
    val isResolving: Boolean
        get() = serverStatus == null && statusError == null
}

sealed interface EmailUiAction {
    /** "Set it up" — opens the extend-permissions dialog. */
    data object SetupClicked : EmailUiAction

    /** "Dismiss" on onboarding: hide the toolbar icon; Home keeps the entry. */
    data object DismissOnboardingClicked : EmailUiAction

    /** Re-ask the server; used by the retry on the no-email screen and on resume. */
    data object RefreshStatusClicked : EmailUiAction

    /** Opens the chosen mail app, if this platform knows how. */
    data object OpenMailClientClicked : EmailUiAction
}

sealed interface EmailUiEvent {
    /** The email drive is mounted — setup can proceed. */
    data object Activated : EmailUiEvent

    data object CloseOnboarding : EmailUiEvent

    /** The chosen app could not be launched — almost always because it is not installed. */
    data class MailClientUnavailable(val displayName: String) : EmailUiEvent
}

sealed interface EmailError {
    /** The status call failed. Distinct from "the server says it has no email". */
    data object StatusUnavailable : EmailError
}
