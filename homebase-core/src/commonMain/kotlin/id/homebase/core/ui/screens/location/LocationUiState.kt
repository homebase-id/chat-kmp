package id.homebase.core.ui.screens.location

import id.homebase.api.common.OdinId
import id.homebase.core.location.LocationMapProvider
import id.homebase.core.ui.screens.location.devices.LocationDeviceInfo
import id.homebase.core.ui.screens.location.history.DeviceTrace

data class LocationUiState(
    val isCheckingPermissions: Boolean = false,
    val setupInitiated: Boolean = false,
    // Main-screen state
    val activated: Boolean = false,
    val trackingEnabled: Boolean = false,
    val trackingAvailable: Boolean = false,
    /** Bottom-nav icon visibility (soft-launch opt-in), toggled on the Setup screen. */
    val iconVisible: Boolean = false,
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
    // Dashboard state
    val devices: List<LocationDeviceInfo> = emptyList(),
    val todayTraces: List<DeviceTrace> = emptyList(),
    /** Members of the "Emergency Location Access" circle (their profile pics show on the dashboard). */
    val emergencyContacts: List<OdinId> = emptyList(),
    val mapProvider: LocationMapProvider = LocationMapProvider.DEFAULT,
) {
    /** Only OSM tiles are implemented today; the canvas takes a boolean. */
    val showMapTiles: Boolean get() = mapProvider == LocationMapProvider.OpenStreetMap
}

/**
 * Main-screen body switch: dashboard once the add-on runs, setup otherwise.
 * Viewer devices (desktop/web, trackerAvailable = false) never have
 * trackingEnabled — once activated they are pure viewers and always get the
 * dashboard; requiring the switch would strand them on Setup forever.
 */
fun isDashboard(
    activated: Boolean,
    trackingEnabled: Boolean,
    trackerAvailable: Boolean,
    setupOverride: Boolean,
): Boolean = !setupOverride && activated && (trackingEnabled || !trackerAvailable)

sealed interface LocationUiAction {
    data object SetupClicked : LocationUiAction
    data object DismissOnboardingClicked : LocationUiAction
    data class SetTrackingEnabled(val enabled: Boolean) : LocationUiAction
    data class SetIconVisible(val visible: Boolean) : LocationUiAction
    data class SetMapProvider(val provider: LocationMapProvider) : LocationUiAction
    data object RequestWhileInUseClicked : LocationUiAction
    data object RequestAlwaysClicked : LocationUiAction
    data object OpenSystemSettingsClicked : LocationUiAction
}

sealed interface LocationUiEvent {
    data object Activated : LocationUiEvent
    data object CloseOnboarding : LocationUiEvent
}
