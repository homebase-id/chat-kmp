package id.homebase.core.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.util.PlatformInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val youAuthFlowManager: YouAuthFlowManager,
    private val credentialsManager: CredentialsManager,
    platformInfo: PlatformInfo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            loggedInDomain = "",
            appVersion = platformInfo.versionName,
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            loadSettings()
        }
    }

    private suspend fun loadSettings() {
        val credentials = credentialsManager.requireActiveCredentials()
        _uiState.value = _uiState.value.copy(
            loggedInDomain = credentials.domain.domainName
        )
    }

    fun onAction(action: SettingsUiAction) {
        when (action) {
            is SettingsUiAction.LogoutClicked -> {
                viewModelScope.launch {
                    youAuthFlowManager.logout()
                    sendEvent(SettingsUiEvent.LoggedOut)
                }
            }

            SettingsUiAction.OpenOwnerConsoleClicked -> {
                sendEvent(SettingsUiEvent.OpenUrl("https://${uiState.value.loggedInDomain}/owner/settings/delete"))
            }

            SettingsUiAction.DeleteAccount -> {
                _uiState.update { it.copy(uiDialog = SettingsUiDialog.DeleteAccount) }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update {
            it.copy(uiEvent = null)
        }
    }

    fun dialogClosed() {
        _uiState.update { it.copy(uiDialog = null) }
    }

    private fun sendEvent(event: SettingsUiEvent) {
        _uiState.update { it.copy(uiEvent = event) }
    }
}
