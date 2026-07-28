package id.homebase.core.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.logging.LoggerConfig
import id.homebase.core.notifications.NotificationService
import id.homebase.core.notifications.SubscriptionVerificationStatus
import id.homebase.core.settings.UserPreferences
import id.homebase.core.share.ShareCacheStorage
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
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private companion object {
        const val TAG = "Settings"
    }

    private val _uiState = MutableStateFlow(
        SettingsUiState(useNativeFeed = userPreferences.useNativeFeed),
    )
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
                sendEvent(SettingsUiEvent.NavigateToProfileEdit)
            }

            SettingsUiAction.AvatarClicked -> {
                sendEvent(SettingsUiEvent.NavigateToProfileAvatarEdit)
            }

            SettingsUiAction.SecuritySetupClicked -> {
                uiState.value.ownerSession?.let {
                    sendEvent(SettingsUiEvent.OpenUrl("https://${it.odinId.domainName}/owner/security"))
                }
            }

            SettingsUiAction.DeleteAccount -> {
                _uiState.update { it.copy(uiDialog = SettingsUiDialog.DeleteAccount) }
            }

            is SettingsUiAction.SetUseNativeFeed -> {
                userPreferences.useNativeFeed = action.enabled
                _uiState.update { it.copy(useNativeFeed = action.enabled) }
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
        // Guard against a second tap while logout is in progress — the overlay is the
        // primary defence, but the VM is the authoritative one-in-flight gate.
        if (_uiState.value.isLoggingOut) return
        _uiState.update { it.copy(isLoggingOut = true) }

        viewModelScope.launch {
            // Pre-steps are best-effort: a failing log purge or cache clear must not stop the
            // actual logout below. Before this guard, a throw here left isLoggingOut stuck true,
            // which permanently deadened the logout button for the rest of the process.
            runCatching { LoggerConfig.purgeLogs() }
                .onFailure { Logger.e(throwable = it, tag = TAG) { "purgeLogs failed" } }
            runCatching { notificationService.deleteToken() }
                .onFailure { Logger.e(throwable = it, tag = TAG) { "deleteToken failed" } }
            runCatching { shareCacheStorage.clearConversationCache() }
                .onFailure { Logger.e(throwable = it, tag = TAG) { "clearConversationCache failed" } }

            youAuthFlowManager.logout()
            sendEvent(SettingsUiEvent.LoggedOut)
        }
    }
}
