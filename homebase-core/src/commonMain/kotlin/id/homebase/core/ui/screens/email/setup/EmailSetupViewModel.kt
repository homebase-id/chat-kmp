package id.homebase.core.ui.screens.email.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.mail.MailProvider
import id.homebase.core.ui.screens.email.EmailService
import id.homebase.core.ui.screens.email.EmailStream
import id.homebase.core.ui.screens.email.model.EmailCredentialContent
import kotlinx.coroutines.flow.MutableStateFlow
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

    init {
        // mail@<identity> by default; the user can still change it before the mailbox is created,
        // after which it is fixed because it is what mail is addressed to.
        viewModelScope.launch {
            val domain = credentialsManager.getActiveCredentials()?.domain?.domainName ?: return@launch
            _uiState.update { state ->
                if (state.primaryEmailAddress.isEmpty()) {
                    state.copy(primaryEmailAddress = "mail@$domain")
                } else {
                    state
                }
            }
        }
    }

    fun onAction(action: EmailSetupUiAction) {
        when (action) {
            is EmailSetupUiAction.AddressChanged ->
                _uiState.update { it.copy(primaryEmailAddress = action.address) }

            EmailSetupUiAction.CreateMailboxClicked -> runStep(EmailSetupStep.NeedsMailbox) {
                val result = mailProvider.ensureMailbox(_uiState.value.primaryEmailAddress)
                _uiState.update { it.copy(dnsRecordsWritten = result.dnsRecordsWritten) }
            }

            is EmailSetupUiAction.GenerateKeyClicked -> runStep(EmailSetupStep.NeedsKey) {
                mailProvider.generateKey(
                    primaryEmailAddress = _uiState.value.primaryEmailAddress,
                    clientEntropyBase64 = action.clientEntropyBase64,
                )
            }

            EmailSetupUiAction.IssueCredentialClicked -> runStep(EmailSetupStep.NeedsAppPassword) {
                issueFirstCredential()
            }

            EmailSetupUiAction.ErrorDismissed -> _uiState.update { it.copy(error = null) }
        }
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

    /**
     * Runs one step, then refreshes the drive so the derived step advances. Failures surface as a
     * message rather than a crash: every step here is retryable by design.
     */
    private fun runStep(step: EmailSetupStep, block: suspend () -> Unit) {
        if (_uiState.value.runningStep != null) return

        viewModelScope.launch {
            _uiState.update { it.copy(runningStep = step, error = null) }
            try {
                block()
                emailStream.loadAll()
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Setup step $step failed" }
                _uiState.update { it.copy(error = e.message ?: "That step did not complete") }
            } finally {
                _uiState.update { it.copy(runningStep = null) }
            }
        }
    }
}

data class EmailSetupUiState(
    val primaryEmailAddress: String = "",
    /** Which step is in flight, so the UI can disable the rest. */
    val runningStep: EmailSetupStep? = null,
    val error: String? = null,
    /** False for manual-DNS identities: the records are instructions, not something we wrote. */
    val dnsRecordsWritten: Boolean = true,
)

sealed interface EmailSetupUiAction {
    data class AddressChanged(val address: String) : EmailSetupUiAction
    data object CreateMailboxClicked : EmailSetupUiAction

    /** Entropy is optional — empty on platforms with no motion sensor. */
    data class GenerateKeyClicked(val clientEntropyBase64: String = "") : EmailSetupUiAction

    data object IssueCredentialClicked : EmailSetupUiAction
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
