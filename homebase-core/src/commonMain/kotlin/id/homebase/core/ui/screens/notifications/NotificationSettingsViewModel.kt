package id.homebase.core.ui.screens.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.notifications.NotificationService
import id.homebase.core.notifications.SubscriptionVerificationStatus
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

    private var debugTapCount = 0
    private val _uiState = MutableStateFlow(NotificationSettingsUiState())
    val uiState: StateFlow<NotificationSettingsUiState> = _uiState.asStateFlow()

    init {
        loadPreferences()
        loadNotificationStatus()
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

    private fun loadNotificationStatus() {
        viewModelScope.launch {
            try {
                val token = notificationService.getToken()
                _uiState.update {
                    it.copy(
                        deviceToken = token,
                        registrationStatus = if (token != null) RegistrationStatus.REGISTERED
                        else RegistrationStatus.NOT_REGISTERED
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(registrationStatus = RegistrationStatus.ERROR)
                }
            }
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
                    _uiState.update { it.copy(isReRegistering = true, reRegisterResult = null) }
                    val result = notificationService.reRegister()
                    result.fold(
                        onSuccess = { token ->
                            _uiState.update {
                                it.copy(
                                    isReRegistering = false,
                                    deviceToken = token,
                                    registrationStatus = if (token != null) RegistrationStatus.REGISTERED
                                    else RegistrationStatus.NOT_REGISTERED,
                                    reRegisterResult = if (token != null) ReRegisterResult.Success(token)
                                    else ReRegisterResult.Failure("Failed to obtain push token")
                                )
                            }
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(
                                    isReRegistering = false,
                                    registrationStatus = RegistrationStatus.ERROR,
                                    reRegisterResult = ReRegisterResult.Failure(
                                        error.message ?: "Unknown error"
                                    )
                                )
                            }
                        }
                    )
                }
            }

            NotificationSettingsUiAction.DismissReRegisterResult -> {
                _uiState.update { it.copy(reRegisterResult = null) }
            }

            NotificationSettingsUiAction.DebugHeaderTapped -> {
                if (_uiState.value.showDebugInfo) {
                    debugTapCount = 0
                    _uiState.update { it.copy(showDebugInfo = false) }
                } else {
                    debugTapCount++
                    if (debugTapCount >= 5) {
                        _uiState.update { it.copy(showDebugInfo = true) }
                        verifyServerSubscription()
                    }
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

    private fun verifyServerSubscription() {
        viewModelScope.launch {
            _uiState.update { it.copy(isVerifyingSubscription = true, subscriptionVerification = null) }
            try {
                val detail = notificationService.verifySubscription()
                val statusText = when (detail.status) {
                    SubscriptionVerificationStatus.OK -> "OK"
                    SubscriptionVerificationStatus.NOT_REGISTERED -> "Not Registered"
                    SubscriptionVerificationStatus.NO_LOCAL_TOKEN -> "No Local Token"
                    SubscriptionVerificationStatus.TOKEN_MISMATCH -> "Token Mismatch"
                }
                _uiState.update {
                    it.copy(
                        isVerifyingSubscription = false,
                        subscriptionVerification = SubscriptionVerificationResult(
                            status = statusText,
                            serverToken = detail.serverToken,
                            friendlyName = detail.friendlyName,
                            isOk = detail.status == SubscriptionVerificationStatus.OK
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isVerifyingSubscription = false,
                        subscriptionVerification = SubscriptionVerificationResult(
                            status = "Error: ${e.message}",
                            isOk = false
                        )
                    )
                }
            }
        }
    }

    fun updatePermissionStatus(isGranted: Boolean, isPermanentlyDenied: Boolean = false) {
        _uiState.update {
            it.copy(
                isPermissionGranted = isGranted,
                isPermissionPermanentlyDenied = !isGranted && isPermanentlyDenied
            )
        }
    }
}
