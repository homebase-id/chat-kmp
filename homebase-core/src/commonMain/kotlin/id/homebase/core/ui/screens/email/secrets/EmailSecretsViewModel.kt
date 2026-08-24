package id.homebase.core.ui.screens.email.secrets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.mail.MailProvider
import id.homebase.core.ui.screens.email.EmailService
import id.homebase.core.ui.screens.email.EmailStream
import id.homebase.core.ui.screens.email.model.EmailCredential
import id.homebase.core.ui.screens.email.model.EmailKeyRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * The secrets the identity holds: mail-client credentials and encryption keys.
 *
 * Both lists come off the drive, so this screen works offline and shows the same thing on every
 * device. Revealing is per-item and never sticky — a secret on screen is only there because the
 * user just asked for it.
 */
class EmailSecretsViewModel(
    private val mailProvider: MailProvider,
    private val emailService: EmailService,
    private val emailStream: EmailStream,
) : ViewModel() {

    companion object {
        private const val TAG = "EmailSecretsViewModel"
    }

    private val _revealed = MutableStateFlow<Set<String>>(emptySet())
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    private val _error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<EmailSecretsUiState> = combine(
        emailStream.credentials,
        emailStream.keys,
        emailStream.currentKeyFileId,
        _revealed,
        combine(_busy, _error) { busy, error -> busy to error },
    ) { credentials, keys, currentKey, revealed, (busy, error) ->
        EmailSecretsUiState(
            credentials = credentials,
            keys = keys,
            currentKeyFileId = currentKey,
            revealedIds = revealed,
            busyIds = busy,
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EmailSecretsUiState())

    fun onAction(action: EmailSecretsUiAction) {
        when (action) {
            is EmailSecretsUiAction.ToggleReveal -> _revealed.update { revealed ->
                if (action.id in revealed) revealed - action.id else revealed + action.id
            }

            is EmailSecretsUiAction.Revoke -> revoke(action.credential)

            EmailSecretsUiAction.ErrorDismissed -> _error.value = null
        }
    }

    /**
     * Revoke on the server FIRST, then forget our record. The other order would leave a credential
     * live on the mail server with nothing pointing at it — unrevokable, because the id is the
     * only handle and it lived in the file we just deleted.
     */
    private fun revoke(credential: EmailCredential) {
        if (credential.id in _busy.value) return

        viewModelScope.launch {
            _busy.update { it + credential.id }
            try {
                mailProvider.revokeAppPassword(credential.id)
                emailService.forgetCredential(credential.fileId)
                emailStream.loadAll()
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Failed to revoke ${credential.id}" }
                _error.value = e.message ?: "That credential could not be revoked"
            } finally {
                _busy.update { it - credential.id }
            }
        }
    }
}

data class EmailSecretsUiState(
    val credentials: List<EmailCredential> = emptyList(),
    val keys: List<EmailKeyRef> = emptyList(),
    val currentKeyFileId: Uuid? = null,
    /** Which secrets the user has asked to see right now. */
    val revealedIds: Set<String> = emptySet(),
    val busyIds: Set<String> = emptySet(),
    val error: String? = null,
)

sealed interface EmailSecretsUiAction {
    data class ToggleReveal(val id: String) : EmailSecretsUiAction
    data class Revoke(val credential: EmailCredential) : EmailSecretsUiAction
    data object ErrorDismissed : EmailSecretsUiAction
}
