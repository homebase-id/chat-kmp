package id.homebase.core.ui.screens.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.notifications.NotificationService
import id.homebase.core.settings.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NotificationSettingsViewModel(
    private val userPreferences: UserPreferences,
    private val notificationService: NotificationService
) : ViewModel() {

    private val _uiState = MutableStateFlow(NotificationSettingsUiState())
    val uiState: StateFlow<NotificationSettingsUiState> = _uiState.asStateFlow()

    init {
        loadPreferences()
    }

    private fun loadPreferences() {
        _uiState.update {
            it.copy(
                playWhileAppOpen = userPreferences.playWhileAppOpen,
                notificationContentLevel = NotificationContentLevel.fromCode(
                    userPreferences.notificationContentLevel
                ),
                includeMutedChatsInBadge = userPreferences.includeMutedChatsInBadge,
            )
        }
    }

    fun onAction(action: NotificationSettingsUiAction) {
        when (action) {
            is NotificationSettingsUiAction.SetPlayWhileAppOpen -> {
                userPreferences.playWhileAppOpen = action.enabled
                _uiState.update { it.copy(playWhileAppOpen = action.enabled) }
            }

            is NotificationSettingsUiAction.SetContentLevel -> {
                userPreferences.notificationContentLevel = action.level.code
                _uiState.update {
                    it.copy(notificationContentLevel = action.level, showContentLevelPicker = false)
                }
            }

            is NotificationSettingsUiAction.SetIncludeMutedChatsInBadge -> {
                userPreferences.includeMutedChatsInBadge = action.enabled
                _uiState.update { it.copy(includeMutedChatsInBadge = action.enabled) }
            }


            NotificationSettingsUiAction.ToggleContentLevelPicker -> {
                _uiState.update { it.copy(showContentLevelPicker = !it.showContentLevelPicker) }
            }

            NotificationSettingsUiAction.ReRegisterPushNotifications -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(isReRegistering = true) }
                    notificationService.reRegister()
                    _uiState.update { it.copy(isReRegistering = false) }
                }
            }

            NotificationSettingsUiAction.RequestPermission -> {
                // Handled by UI
            }

            NotificationSettingsUiAction.OpenSystemNotificationSettings -> {
                // Handled by the screen composable — triggers platform-specific system settings
            }
        }
    }

    fun updatePermissionStatus(isGranted: Boolean) {
        _uiState.update { it.copy(isPermissionGranted = isGranted) }
    }
}
