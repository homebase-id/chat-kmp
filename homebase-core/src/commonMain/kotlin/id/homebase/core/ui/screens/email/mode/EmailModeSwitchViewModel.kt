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

    fun onAction(action: EmailModeSwitchUiAction) {
        when (action) {
            is EmailModeSwitchUiAction.AcknowledgedChanged ->
                _uiState.update { it.copy(acknowledged = action.acknowledged) }

            is EmailModeSwitchUiAction.TypedAddressChanged ->
                _uiState.update { it.copy(typedAddress = action.typed) }

            is EmailModeSwitchUiAction.ConfirmClicked -> switchTo(action.target)
        }
    }

    private fun switchTo(target: MailboxMode) {
        if (_uiState.value.busy) return

        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, switchFailed = false) }
            try {
                mailProvider.setMode(target)
                _events.emit(EmailModeSwitchUiEvent.Switched)
            } catch (e: Exception) {
                Logger.e(e, TAG) { "Switching the mailbox to $target failed" }
                _uiState.update { it.copy(switchFailed = true) }
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private companion object {
        const val TAG = "EmailModeSwitchViewModel"
    }
}

fun canConfirmModeSwitch(acknowledged: Boolean, typedAddress: String, address: String): Boolean =
    acknowledged && address.isNotBlank() && typedAddress.trim().equals(address.trim(), ignoreCase = true)

data class EmailModeSwitchUiState(
    val acknowledged: Boolean = false,
    val typedAddress: String = "",
    val busy: Boolean = false,
    val switchFailed: Boolean = false,
)

sealed interface EmailModeSwitchUiAction {
    data class AcknowledgedChanged(val acknowledged: Boolean) : EmailModeSwitchUiAction
    data class TypedAddressChanged(val typed: String) : EmailModeSwitchUiAction
    data class ConfirmClicked(val target: MailboxMode) : EmailModeSwitchUiAction
}

sealed interface EmailModeSwitchUiEvent {
    data object Switched : EmailModeSwitchUiEvent
}
