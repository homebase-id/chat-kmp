package id.homebase.core.ui.screens.email.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.mail.MailProvider
import id.homebase.core.ui.screens.email.EmailService
import id.homebase.core.ui.screens.email.EmailStream
import id.homebase.core.ui.screens.email.model.EmailCredentialContent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Runs the setup steps the server owns: create the mailbox, generate the key, issue a first
 * credential.
 *
 * Nothing here tracks progress. Each action re-reads the server's status afterwards and the step
 * is derived from that, so an app killed mid-flow resumes simply by asking again — which is also
 * why every action is safe to press twice.
 */
class EmailSetupViewModel(
    private val mailProvider: MailProvider,
    private val emailService: EmailService,
    private val emailStream: EmailStream,
    private val credentialsManager: CredentialsManager,
) : ViewModel() {

    companion object {
        private const val TAG = "EmailSetupViewModel"
        private const val FIRST_CREDENTIAL_LABEL = "This device"
    }

    private val _uiState = MutableStateFlow(EmailSetupUiState())
    val uiState: StateFlow<EmailSetupUiState> = _uiState.asStateFlow()

    /**
     * Emitted after a step completes. The server's status is what decides which step is current,
     * and this ViewModel does not own it — without this the checklist never advances, the button
     * keeps offering the step that just succeeded, and pressing it again silently does the work
     * a second time. For key generation that means a real extra rotation each press.
     */
    private val _stepCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val stepCompleted: SharedFlow<Unit> = _stepCompleted.asSharedFlow()

    init {
        // Always mail@<identity>, and not editable. One address, derived from the identity, is
        // one less thing to get wrong while the rest of the flow is being proven; additional
        // names belong to an alias manager, which is a separate feature with its own rules
        // (each alias has to be provisioned into the mail server and published in WKD).
        viewModelScope.launch {
            val domain = credentialsManager.getActiveCredentials()?.domain?.domainName ?: return@launch
            // Unconditional: the address is a pure function of the signed-in identity, so any
            // value already in state belongs to whoever was signed in before. An isEmpty() guard
            // here meant a surviving ViewModel kept the previous identity's address and showed
            // it to the new one - "always mail@<identity>" has to be enforced, not assumed.
            _uiState.update { state -> state.copy(primaryEmailAddress = "mail@$domain") }
        }
    }

    fun onAction(action: EmailSetupUiAction) {
        when (action) {
            EmailSetupUiAction.ErrorDismissed -> _uiState.update { it.copy(error = null) }
        }
    }

    /**
     * Runs setup to completion: mailbox, then key, then the first app password.
     *
     * One action rather than a button per step, because the steps are not choices — the order is
     * fixed by the server (no credential before a published key) and every one of them is
     * something the user wants. A button per step also made it possible to press the same one
     * twice, and for key generation a second press is a real key rotation.
     *
     * Which step runs is read back from [currentStep] after each one, so this resumes an
     * interrupted setup and never repeats work that is already done. That is what guarantees
     * exactly one key: the key step is only reachable while the identity has none.
     */
    fun runSetup(currentStep: () -> EmailSetupStep, refresh: suspend () -> Unit) {
        if (_uiState.value.runningStep != null) return

        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }

            try {
                while (true) {
                    val step = currentStep()
                    val work = workFor(step) ?: break

                    _uiState.update { it.copy(runningStep = step) }
                    work()
                    emailStream.loadAll()
                    refresh()

                    // The step must advance. If it does not, the server disagrees with us about
                    // what just happened, and looping would redo the work — which for the key step
                    // means rotating again on every pass.
                    if (currentStep() == step) {
                        _uiState.update { it.copy(error = EmailSetupError.Stalled) }
                        break
                    }
                }
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Setup stopped" }
                _uiState.update { it.copy(error = EmailSetupError.Failed(e.message)) }
            } finally {
                _uiState.update { it.copy(runningStep = null) }
            }
        }
    }

    private fun workFor(step: EmailSetupStep): (suspend () -> Unit)? = when (step) {
        EmailSetupStep.NeedsMailbox -> {
            {
                val result = mailProvider.ensureMailbox(_uiState.value.primaryEmailAddress)
                _uiState.update { it.copy(dnsRecordsWritten = result.dnsRecordsWritten) }
            }
        }

        EmailSetupStep.NeedsKey -> {
            { mailProvider.generateKey(primaryEmailAddress = _uiState.value.primaryEmailAddress) }
        }

        EmailSetupStep.NeedsAppPassword -> {
            { issueCredential(FIRST_CREDENTIAL_LABEL) }
        }

        // Permissions and the drive happen before this screen; Complete is done.
        else -> null
    }

    /**
     * Issues the credential and records it on the drive BEFORE it can be shown. If the drive write
     * fails the credential is revoked again rather than left live on the mail server with nobody
     * holding a record of it — a lost secret is only recoverable by revoke-and-reissue, so the
     * failure has to be cleaned up here.
     */
    private suspend fun issueCredential(label: String) {
        val address = _uiState.value.primaryEmailAddress
        val issued = mailProvider.issueAppPassword(address, label)

        val saved = emailService.saveCredential(
            EmailCredentialContent(
                id = issued.id,
                label = issued.label.ifEmpty { label },
                secret = issued.secret,
                emailAddress = address,
                createdUtc = issued.createdAt,
            )
        )

        if (!saved) {
            Logger.w(tag = TAG) { "Could not record credential ${issued.id}; revoking it again" }
            runCatching { mailProvider.revokeAppPassword(issued.id) }
            throw IllegalStateException("The credential could not be saved to your email drive")
        }
    }

    private suspend fun issueFirstCredential() = issueCredential(FIRST_CREDENTIAL_LABEL)

}

data class EmailSetupUiState(
    val primaryEmailAddress: String = "",
    /** Which step is in flight, so the UI can disable the rest. */
    val runningStep: EmailSetupStep? = null,
    val error: EmailSetupError? = null,
    /** False for manual-DNS identities: the records are instructions, not something we wrote. */
    val dnsRecordsWritten: Boolean = true,
)

sealed interface EmailSetupUiAction {
    data object ErrorDismissed : EmailSetupUiAction
}

/** Convenience for the screen: the step the server's status implies right now. */
fun EmailSetupStep.isDone(current: EmailSetupStep): Boolean =
    order() < current.order()

private fun EmailSetupStep.order(): Int = when (this) {
    EmailSetupStep.NeedsPermissions -> 0
    EmailSetupStep.NeedsDrive -> 1
    EmailSetupStep.NeedsMailbox -> 2
    EmailSetupStep.NeedsKey -> 3
    EmailSetupStep.NeedsAppPassword -> 4
    EmailSetupStep.Complete -> 5
}

/**
 * Why setup stopped. Typed rather than a message, so the wording lives with the other strings and
 * can be translated — a ViewModel is the wrong place to keep English.
 */
sealed interface EmailSetupError {
    /** The step reported success but the server still says it is pending. */
    data object Stalled : EmailSetupError

    /** Something threw; [message] is the server's own words where there are any. */
    data class Failed(val message: String?) : EmailSetupError
}
