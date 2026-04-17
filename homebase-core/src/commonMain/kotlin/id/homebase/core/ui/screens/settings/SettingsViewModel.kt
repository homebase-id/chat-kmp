package id.homebase.core.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chat_kmp.homebase_common.BuildConfig
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.logging.LoggerConfig
import id.homebase.core.notifications.NotificationService
import id.homebase.core.notifications.SubscriptionVerificationStatus
import id.homebase.core.share.ShareCacheStorage
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
    private val shareCacheStorage: ShareCacheStorage,
    platformInfo: PlatformInfo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(
        appVersion = platformInfo.versionName,
        appBuild = platformInfo.versionCode.toString(),
        appBuildDate = BuildConfig.APP_BUILD_TIME,
    ))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        verifyNotificationSubscription()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            ownerSessionRepository.user.collect { session ->
                _uiState.update { it.copy(ownerSession = session) }
            }
        }
    }

    private fun verifyNotificationSubscription() {
        viewModelScope.launch {
            try {
                val result = notificationService.verifySubscription()
                val status = if (result.status == SubscriptionVerificationStatus.OK)
                    NotificationVerificationStatus.OK
                else
                    NotificationVerificationStatus.ERROR
                _uiState.update { it.copy(notificationStatus = status) }
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(notificationStatus = NotificationVerificationStatus.ERROR)
                }
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

            SettingsUiAction.ProfileInfoClicked -> {
                uiState.value.ownerSession?.let {
                    sendEvent(SettingsUiEvent.OpenUrl("https://${it.odinId.domainName}/owner/profile/standard-info"))
                }
            }

            SettingsUiAction.SecuritySetupClicked -> {
                uiState.value.ownerSession?.let {
                    sendEvent(SettingsUiEvent.OpenUrl("https://${it.odinId.domainName}/owner/security"))
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
            shareCacheStorage.clearConversationCache()
            youAuthFlowManager.logout()
            sendEvent(SettingsUiEvent.LoggedOut)
        }
    }
}
