package id.homebase.core.ui.screens.email.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.mail.MailProvider
import id.homebase.core.email.EmailPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EmailSettingsViewModel(
    private val emailPreferences: EmailPreferences,
    private val mailProvider: MailProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        EmailSettingsUiState(
            iconVisible = emailPreferences.iconVisible.value,
            biometricsEnabled = emailPreferences.biometricsEnabled.value,
        )
    )
    val uiState: StateFlow<EmailSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            emailPreferences.iconVisible.collect { v ->
                _uiState.update { it.copy(iconVisible = v) }
            }
        }
        viewModelScope.launch {
            emailPreferences.biometricsEnabled.collect { v ->
                _uiState.update { it.copy(biometricsEnabled = v) }
            }
        }
    }

    fun onAction(action: EmailSettingsUiAction) {
        when (action) {
            EmailSettingsUiAction.OpenEmailClicked, EmailSettingsUiAction.ChangeModeClicked -> {
                // Handled by the screen — it navigates.
            }
            EmailSettingsUiAction.ScreenShown -> loadMailboxMode()
            is EmailSettingsUiAction.SetIconVisible -> {
                viewModelScope.launch { emailPreferences.setIconVisible(action.visible) }
            }
            is EmailSettingsUiAction.SetBiometricsEnabled -> {
                viewModelScope.launch { emailPreferences.setBiometricsEnabled(action.enabled) }
            }
        }
    }

    // Per showing, not once: the mode may have just been switched on the screen this one opened
    private fun loadMailboxMode() {
        viewModelScope.launch {
            runCatching { mailProvider.getStatus() }
                .onSuccess { status ->
                    val mode = status.mode?.takeIf { status.mailboxProvisioned }
                    _uiState.update { it.copy(mailboxMode = mode) }
                }
                .onFailure { Logger.d(tag = "EmailSettingsViewModel") { "mailbox mode unavailable: ${it.message}" } }
        }
    }
}
