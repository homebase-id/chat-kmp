package id.homebase.core.ui.screens.location

data class LocationUiState(
    val isCheckingPermissions: Boolean = false,
    val setupInitiated: Boolean = false,
    // Main-screen state
    val trackingEnabled: Boolean = false,
    val trackingAvailable: Boolean = false,
    /** Google Play prominent-disclosure consent (persisted; gates first grant/enable). */
    val disclosureAccepted: Boolean = false,
    val whileInUseGranted: Boolean = false,
    val whileInUsePermanentlyDenied: Boolean = false,
    val alwaysGranted: Boolean = false,
    val alwaysPermanentlyDenied: Boolean = false,
    val lastFixEpochMs: Long? = null,
    val lastFixLat: Double? = null,
    val lastFixLon: Double? = null,
    val pointsToday: Int = 0,
    val pendingUploadCount: Int = 0,
    val lastFlushEpochMs: Long? = null,
)

sealed interface LocationUiAction {
    data object SetupClicked : LocationUiAction
    data object DismissOnboardingClicked : LocationUiAction
    data class SetTrackingEnabled(val enabled: Boolean) : LocationUiAction
    data object RequestWhileInUseClicked : LocationUiAction
    data object RequestAlwaysClicked : LocationUiAction
    data object OpenSystemSettingsClicked : LocationUiAction
}

sealed interface LocationUiEvent {
    data object Activated : LocationUiEvent
    data object CloseOnboarding : LocationUiEvent
}
