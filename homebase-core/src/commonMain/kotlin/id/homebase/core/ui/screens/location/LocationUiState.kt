package id.homebase.core.ui.screens.location

import id.homebase.chat.data.ContactUiModel
import id.homebase.core.location.LocationMapProvider
import id.homebase.core.ui.screens.location.devices.LocationDeviceInfo
import id.homebase.core.ui.screens.location.history.DeviceTrace

data class LocationUiState(
    val isCheckingPermissions: Boolean = false,
    val setupInitiated: Boolean = false,
    // Main-screen state
    val activated: Boolean = false,
    val allowLocationHistory: Boolean = false,
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
    /** Members of our emergency-location-access circle (the "who can locate you" list on the
     *  dashboard) — read from circle membership, the source of truth, not an app-data flag. */
    val whoCanLocateMe: List<ContactUiModel> = emptyList(),
    /** False until circle membership has loaded at least once (drives the loading spinner). */
    val whoCanLocateMeLoaded: Boolean = false,
    /** Contacts we can locate (the `iCanLocate` app-data flag) — the "who you can locate" list. */
    val whoICanLocate: List<ContactUiModel> = emptyList(),
    /** False until the locatable-contacts list has loaded at least once (drives the spinner). */
    val whoICanLocateLoaded: Boolean = false,
    /** Owner-console deep link to manage the Emergency Location Access circle (the actual location
     *  drive grant); null until the identity is known. */
    val emergencyManageUrl: String? = null,
    val mapProvider: LocationMapProvider = LocationMapProvider.DEFAULT,
    /** Show the "Live location sharing" dashboard section: I'm sharing, or a recent inbound point exists. */
    val liveSharingVisible: Boolean = false,
    /** People I'm sharing my live location with — deduped by identity, longest end-time. */
    val outgoingShares: List<OutgoingShareRow> = emptyList(),
    /** People sharing their live location with me — with the age of their last fix. */
    val incomingShares: List<IncomingShareRow> = emptyList(),
) {
    /** Only OSM tiles are implemented today; the canvas takes a boolean. */
    val showMapTiles: Boolean get() = mapProvider == LocationMapProvider.OpenStreetMap
}

/** One row in the "Sharing with" list: a person and the latest time my share to them lasts. */
data class OutgoingShareRow(
    /** Identity domain string (OdinId.domainName) — stable list key and stop target. */
    val odinId: String,
    val name: String,
    val avatarInitials: String,
    val avatarUrl: String?,
    /** Longest end-time across this person's overlapping shares (UTC epoch-ms). */
    val untilMs: Long,
)

/** One row in the "Sharing with you" list: a person and how stale their last fix is. */
data class IncomingShareRow(
    val odinId: String,
    val name: String,
    val avatarInitials: String,
    val avatarUrl: String?,
    /** Age of their last received fix (ms); the label only shows past AGE_LABEL_AFTER_MS. */
    val ageMs: Long,
)

/**
 * Main-screen body switch: dashboard once the add-on is fully set up, setup otherwise.
 *
 * A tracker device reaches the dashboard once it is [activated] AND its location permissions
 * are complete ([permissionsComplete] = while-in-use AND always/background both granted). The
 * landing keys on the GRANTS, not the tracking master switch: a user with both grants but
 * "Track my location" off still wants the dashboard (to view live shares / others), so gating
 * on the toggle wrongly stranded them on Setup (bug #822). Conversely, with both grants denied,
 * permissionsComplete is false and they land on Setup — where they can grant access. A
 * while-in-use-only grant likewise keeps Setup as the default so the background grant can be
 * finished.
 *
 * Viewer devices (desktop/web, trackerAvailable = false) never track or request permissions —
 * once activated they are pure viewers and always get the dashboard; requiring permissions
 * would strand them on Setup forever.
 */
fun isDashboard(
    activated: Boolean,
    trackerAvailable: Boolean,
    permissionsComplete: Boolean,
    setupOverride: Boolean,
): Boolean = !setupOverride && activated &&
    (!trackerAvailable || permissionsComplete)

sealed interface LocationUiAction {
    data object SetupClicked : LocationUiAction
    data object DismissOnboardingClicked : LocationUiAction
    data class SetAllowLocationHistory(val enabled: Boolean) : LocationUiAction
    data class SetIconVisible(val visible: Boolean) : LocationUiAction
    data class SetMapProvider(val provider: LocationMapProvider) : LocationUiAction
    /** Stop all of one person's outgoing live shares (Dashboard per-row stop). */
    data class StopSharingWith(val odinId: String) : LocationUiAction
    /** Stop every outgoing live share (Dashboard "stop sharing with everyone"). */
    data object StopSharingWithEveryone : LocationUiAction
    data object RequestWhileInUseClicked : LocationUiAction
    data object RequestAlwaysClicked : LocationUiAction
    data object OpenSystemSettingsClicked : LocationUiAction
}

sealed interface LocationUiEvent {
    data object Activated : LocationUiEvent
    data object CloseOnboarding : LocationUiEvent
}
