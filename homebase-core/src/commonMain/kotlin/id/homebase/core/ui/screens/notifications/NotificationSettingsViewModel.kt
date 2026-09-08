package id.homebase.core.ui.screens.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.notifications.NotificationService
import id.homebase.core.notifications.SubscriptionVerificationStatus
import id.homebase.core.notifications.WebPushHealth
import id.homebase.core.notifications.WebPushService
import id.homebase.core.settings.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NotificationSettingsViewModel(
    private val userPreferences: UserPreferences,
    private val notificationService: NotificationService,
    private val webPushService: WebPushService,
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
        if (webPushService.isSupported) {
            loadWebPushStatus()
            return
        }
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

    /**
     * The FCM device-token status is meaningless for a browser subscription — the server redacts
     * the endpoint and keys, so it always reads back as a token mismatch.
     */
    private fun loadWebPushStatus() {
        viewModelScope.launch {
            applyWebPushHealth(
                runCatching { webPushService.evaluate() }.getOrDefault(WebPushHealth.UNSUPPORTED)
            )
        }
    }

    /**
     * Health is the whole permission answer on web — it is read from `Notification.permission` —
     * so it, not the permission callback, decides which dead end the permission card explains.
     */
    private fun applyWebPushHealth(health: WebPushHealth, result: ReRegisterResult? = null) {
        _uiState.update {
            it.copy(
                deviceToken = null,
                isReRegistering = false,
                isPermissionGranted = health == WebPushHealth.SUBSCRIBED ||
                        health == WebPushHealth.NEEDS_REPAIR,
                isPermissionPermanentlyDenied = health == WebPushHealth.BLOCKED,
                needsHomeScreenInstall = health == WebPushHealth.NEEDS_INSTALL,
                registrationStatus = when (health) {
                    WebPushHealth.SUBSCRIBED -> RegistrationStatus.REGISTERED
                    WebPushHealth.NOT_SUBSCRIBED, WebPushHealth.NEEDS_INSTALL ->
                        RegistrationStatus.NOT_REGISTERED

                    WebPushHealth.BLOCKED, WebPushHealth.NEEDS_REPAIR,
                    WebPushHealth.UNSUPPORTED -> RegistrationStatus.ERROR
                },
                reRegisterResult = result,
            )
        }
    }

    /**
     * Repair on web. The card click is the user gesture a browser gates its permission prompt on,
     * so this both enables and re-subscribes; the FCM path would drop the working subscription and
     * then fail to find a device token. Every failure is already explained by the permission card
     * or the status row, so only success gets a banner.
     */
    private fun reRegisterWebPush() {
        viewModelScope.launch {
            _uiState.update { it.copy(isReRegistering = true, reRegisterResult = null) }
            val health = runCatching { webPushService.enable() }
                .getOrDefault(WebPushHealth.UNSUPPORTED)
            applyWebPushHealth(
                health,
                ReRegisterResult.Success.takeIf { health == WebPushHealth.SUBSCRIBED },
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
                if (webPushService.isSupported) return reRegisterWebPush()
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
                                    reRegisterResult = if (token != null) ReRegisterResult.Success
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

    /** FCM only: on web the server redacts the keys, so this can only ever report a mismatch. */
    private fun verifyServerSubscription() {
        if (webPushService.isSupported) return
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
        // A browser grant only opens the door; the subscription still has to be created and
        // posted, which the FCM path gets for free from its token listener. evaluate() sees the
        // now-granted-but-unsubscribed state as NEEDS_REPAIR and does exactly that. It runs on a
        // refusal too, so a pre-existing denial is recognised as one before the user retries it.
        if (webPushService.isSupported) loadWebPushStatus()
    }
}
