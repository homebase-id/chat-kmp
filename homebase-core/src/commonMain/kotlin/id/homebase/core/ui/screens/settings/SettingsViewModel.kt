package id.homebase.core.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.logging.LoggerConfig
import id.homebase.core.notifications.NotificationService
import id.homebase.core.util.PlatformInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val youAuthFlowManager: YouAuthFlowManager,
    private val ownerSessionRepository: OwnerSessionRepository,
    private val notificationService: NotificationService,
    platformInfo: PlatformInfo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(appVersion = platformInfo.versionName))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            ownerSessionRepository.user.collect { session ->
                _uiState.update { it.copy(ownerSession = session) }
            }
        }
    }

    fun onAction(action: SettingsUiAction) {
        when (action) {
            is SettingsUiAction.LogoutClicked -> {
                handleLogout()
            }

            SettingsUiAction.OpenOwnerConsoleClicked -> {
                uiState.value.ownerSession?.let {
                    sendEvent(SettingsUiEvent.OpenUrl("https://${it.odinId.domainName}/owner/settings/delete"))
                }
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

    private fun handleLogout() {
        viewModelScope.launch {
            LoggerConfig.purgeLogs()
            notificationService.deleteToken()
            youAuthFlowManager.logout()
            sendEvent(SettingsUiEvent.LoggedOut)
        }
    }
}
