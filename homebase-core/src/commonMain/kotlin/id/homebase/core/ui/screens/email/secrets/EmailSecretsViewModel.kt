package id.homebase.core.ui.screens.email.secrets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.mail.MailProvider
import id.homebase.core.ui.screens.email.EmailService
import id.homebase.core.ui.screens.email.EmailStream
import id.homebase.core.ui.screens.email.model.EmailCredential
import id.homebase.core.ui.screens.email.model.EmailKeyRef
import id.homebase.api.client.mail.MailClientSettings
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

        /** Busy-key for the rotation, which is not tied to any one credential. */
        const val ROTATING = "rotating-key"
    }

    private val _revealed = MutableStateFlow<Set<String>>(emptySet())
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    private val _error = MutableStateFlow<String?>(null)

    /**
     * Mail-app settings, from the server. Config-derived and free, so fetched once on entry
     * rather than watched - unlike the credentials and keys above, nothing here changes while
     * the screen is open.
     */
    private val _clientSettings = MutableStateFlow<MailClientSettings?>(null)

    init {
        viewModelScope.launch {
            runCatching { mailProvider.getStatus().clientSettings }
                .onSuccess { _clientSettings.value = it }
                .onFailure { Logger.d(tag = TAG) { "mail client settings unavailable: ${it.message}" } }
        }
    }

    val uiState: StateFlow<EmailSecretsUiState> = combine(
        emailStream.credentials,
        emailStream.keys,
        emailStream.currentKeyFileId,
        _revealed,
        combine(_busy, _error, _clientSettings) { busy, error, settings -> Triple(busy, error, settings) },
    ) { credentials, keys, currentKey, revealed, (busy, error, settings) ->
        EmailSecretsUiState(
            credentials = credentials,
            keys = keys,
            currentKeyFileId = currentKey,
            revealedIds = revealed,
            busyIds = busy,
            error = error,
            clientSettings = settings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EmailSecretsUiState())

    fun onAction(action: EmailSecretsUiAction) {
        when (action) {
            EmailSecretsUiAction.GenerateNewKey -> rotateKey()

            is EmailSecretsUiAction.ToggleReveal -> _revealed.update { revealed ->
                if (action.id in revealed) revealed - action.id else revealed + action.id
            }

            is EmailSecretsUiAction.Revoke -> revoke(action.credential)

            EmailSecretsUiAction.ErrorDismissed -> _error.value = null
        }
    }

    /**
     * Rotation: a new keyring, published, with the old one left on the drive. The server does the
     * work in the right order (durable before published); this only has to name the address the
     * key is bound to, which is the one the current key already carries.
     */
    private fun rotateKey() {
        if (ROTATING in _busy.value) return

        viewModelScope.launch {
            _busy.update { it + ROTATING }
            try {
                val address = emailStream.keys.value.firstOrNull()?.userId
                if (address.isNullOrEmpty()) {
                    _error.value = "There is no existing key to rotate"
                    return@launch
                }

                mailProvider.generateKey(primaryEmailAddress = address)
                emailStream.loadAll()
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Key rotation failed" }
                _error.value = e.message ?: "The new key could not be generated"
            } finally {
                _busy.update { it - ROTATING }
            }
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
    /** Null while loading, or when this host publishes no mail hosts. */
    val clientSettings: MailClientSettings? = null,
    val error: String? = null,
)

sealed interface EmailSecretsUiAction {
    /** Rotate: append a new keyring and publish it. The old one is never deleted. */
    data object GenerateNewKey : EmailSecretsUiAction

    data class ToggleReveal(val id: String) : EmailSecretsUiAction
    data class Revoke(val credential: EmailCredential) : EmailSecretsUiAction
    data object ErrorDismissed : EmailSecretsUiAction
}
