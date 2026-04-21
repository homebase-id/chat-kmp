package id.homebase.core.ui.screens.notifications

/** Notification content display levels. */
enum class NotificationContentLevel(val code: String, val displayName: String) {
    NAME_CONTENT_ACTIONS(
        "name_content_actions",
        "Name, Content, and Actions"
    ),
    NAME_ONLY("name_only", "Name Only"), NO_NAME_OR_CONTENT(
        "no_name_or_content",
        "No Name or Content"
    );

    companion object {
        fun fromCode(code: String): NotificationContentLevel =
            entries.find { it.code == code } ?: NAME_CONTENT_ACTIONS
    }
}

/** Push notification registration status. */
enum class RegistrationStatus {
    UNKNOWN, REGISTERED, NOT_REGISTERED, ERROR
}

/** Result of a re-registration attempt. */
sealed interface ReRegisterResult {
    data class Success(val token: String) : ReRegisterResult
    data class Failure(val message: String) : ReRegisterResult
}

/** UI state for the notification settings screen. */
data class NotificationSettingsUiState(
    val playWhileAppOpen: Boolean = true,
    val notificationContentLevel: NotificationContentLevel = NotificationContentLevel.NAME_CONTENT_ACTIONS,
    val includeMutedChatsInBadge: Boolean = false,
    val isReRegistering: Boolean = false,
    val showContentLevelPicker: Boolean = false,
    val isPermissionGranted: Boolean = false,
    val isPermissionPermanentlyDenied: Boolean = false,
    val deviceToken: String? = null,
    val registrationStatus: RegistrationStatus = RegistrationStatus.UNKNOWN,
    val reRegisterResult: ReRegisterResult? = null,
    val showDebugInfo: Boolean = false,
    val isVerifyingSubscription: Boolean = false,
    val subscriptionVerification: SubscriptionVerificationResult? = null,
)

/** Result of server-side subscription verification. */
data class SubscriptionVerificationResult(
    val status: String,
    val serverToken: String? = null,
    val friendlyName: String? = null,
    val isOk: Boolean = false,
)

/** Actions from the notification settings UI. */
sealed interface NotificationSettingsUiAction {
    data class SetPlayWhileAppOpen(val enabled: Boolean) : NotificationSettingsUiAction
    data class SetContentLevel(val level: NotificationContentLevel) : NotificationSettingsUiAction
    data class SetIncludeMutedChatsInBadge(val enabled: Boolean) : NotificationSettingsUiAction
    data object ToggleContentLevelPicker : NotificationSettingsUiAction
    data object ReRegisterPushNotifications : NotificationSettingsUiAction
    data object RequestPermission : NotificationSettingsUiAction
    data object OpenSystemNotificationSettings : NotificationSettingsUiAction
    data object DismissReRegisterResult : NotificationSettingsUiAction
    data object DebugHeaderTapped : NotificationSettingsUiAction
}
