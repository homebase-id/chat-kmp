package id.homebase.core.ui.screens.email.mode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.mail.MailProvider
import id.homebase.api.client.mail.MailboxMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EmailModeSwitchViewModel(
    private val mailProvider: MailProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EmailModeSwitchUiState())
    val uiState: StateFlow<EmailModeSwitchUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<EmailModeSwitchUiEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<EmailModeSwitchUiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            runCatching { mailProvider.getStatus() }
                .onSuccess { status ->
                    val current = status.mode ?: MailboxMode.Encrypted
                    _uiState.update {
                        it.copy(
                            loading = false,
                            targetMode = if (current == MailboxMode.Encrypted) MailboxMode.Standard else MailboxMode.Encrypted,
                            address = status.primaryEmailAddress.orEmpty(),
                        )
                    }
                }
                .onFailure { e ->
                    Logger.w(e, TAG) { "Could not read the mailbox mode" }
                    _uiState.update { it.copy(loading = false, error = EmailModeSwitchError.LoadFailed) }
                }
        }
    }

    fun onAction(action: EmailModeSwitchUiAction) {
        when (action) {
            is EmailModeSwitchUiAction.AcknowledgedChanged ->
                _uiState.update { it.copy(acknowledged = action.acknowledged) }

            is EmailModeSwitchUiAction.TypedAddressChanged ->
                _uiState.update { it.copy(typedAddress = action.typed) }

            EmailModeSwitchUiAction.ConfirmClicked -> confirm()
        }
    }

    private fun confirm() {
        val state = _uiState.value
        val target = state.targetMode ?: return
        if (state.busy || !canConfirmModeSwitch(state.acknowledged, state.typedAddress, state.address)) return

        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            try {
                mailProvider.setMode(target)
                _events.emit(EmailModeSwitchUiEvent.Switched)
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Switching the mailbox to $target failed" }
                _uiState.update { it.copy(error = EmailModeSwitchError.SwitchFailed) }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private companion object {
        const val TAG = "EmailModeSwitchViewModel"
    }
}

/** Both guards against an accidental switch: the acknowledgement, and the address typed out in full. */
fun canConfirmModeSwitch(acknowledged: Boolean, typedAddress: String, address: String): Boolean =
    acknowledged && address.isNotBlank() && typedAddress.trim().equals(address.trim(), ignoreCase = true)

data class EmailModeSwitchUiState(
    val loading: Boolean = true,
    /** The mode this screen switches to: always the other one. Null until the status is read. */
    val targetMode: MailboxMode? = null,
    val address: String = "",
    val acknowledged: Boolean = false,
    val typedAddress: String = "",
    val busy: Boolean = false,
    val error: EmailModeSwitchError? = null,
)

sealed interface EmailModeSwitchUiAction {
    data class AcknowledgedChanged(val acknowledged: Boolean) : EmailModeSwitchUiAction
    data class TypedAddressChanged(val typed: String) : EmailModeSwitchUiAction
    data object ConfirmClicked : EmailModeSwitchUiAction
}

sealed interface EmailModeSwitchUiEvent {
    data object Switched : EmailModeSwitchUiEvent
}

enum class EmailModeSwitchError { LoadFailed, SwitchFailed }
