package id.homebase.core.ui.screens.location

import id.homebase.chat.data.ContactUiModel
import id.homebase.core.contactbook.LocateVerifyStatus
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
    /**
     * True once the user has tapped Grant on the background ("always") permission at least once this
     * session without it being granted. On Android 11+ background location can't be granted by
     * re-firing the runtime dialog — the OS silently denies repeat requests (the "flash") — so after
     * the first attempt the Setup row routes to system Settings ("Allow all the time") instead of
     * re-offering Grant. Cleared when the grant lands so a later revoke starts a fresh attempt.
     */
    val alwaysRequestAttempted: Boolean = false,
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
    /** Contacts whose emergency-circle grant is still a sealed deposit rather than a real
     *  [whoCanLocateMe] entry — live-read per contact on [LocationViewModel.refresh]. */
    val whoCanLocateMePending: List<ContactUiModel> = emptyList(),
    /** True while the pending-status fan-out is in flight. */
    val whoCanLocateMePendingChecking: Boolean = false,
    /** odinId domains currently being removed from the emergency circle — drives a per-row
     *  spinner in place of the remove "X" so a tap has visible feedback while in flight. */
    val removingEmergencyContacts: Set<String> = emptySet(),
    /** Contacts we can locate (the `iCanLocate` app-data flag) — the "who you can locate" list. */
    val whoICanLocate: List<ContactUiModel> = emptyList(),
    /** False until the locatable-contacts list has loaded at least once (drives the spinner). */
    val whoICanLocateLoaded: Boolean = false,
    /** Per-entry temporal-verify status for the "who I can locate" list, keyed by odinId.domainName
     *  (mirrors EmergencyContactService.status). Absent key = never verified. */
    val whoICanLocateStatus: Map<String, LocateVerifyStatus> = emptyMap(),
    /** People I can locate whose newest data is older than LOCATE_STALE_WARN_MS. */
    val staleLocatableCount: Int = 0,
    val mapProvider: LocationMapProvider = LocationMapProvider.DEFAULT,
    /** Show the "Live location sharing" dashboard section: I'm sharing, or a recent inbound point exists. */
    val liveSharingVisible: Boolean = false,
    /** An emergency locate request (notice + fetch) is running — the panel's Confirm is disabled. */
    val locateSubmitInFlight: Boolean = false,
    /** People I'm sharing my live location with — deduped by identity, longest end-time. */
    val outgoingShares: List<OutgoingShareRow> = emptyList(),
    /** People sharing their live location with me — with the age of their last fix. */
    val incomingShares: List<IncomingShareRow> = emptyList(),
) {
    /** Only OSM tiles are implemented today; the canvas takes a boolean. */
    val showMapTiles: Boolean get() = mapProvider == LocationMapProvider.OpenStreetMap
}

/** Age past which a locate row's freshness label renders in the warning (orange) color (#879). */
const val LOCATE_AGE_WARN_MS = 2 * 60 * 60_000L

/** Compact age-label bucket for a locate row: the unit to render and its (non-negative) value. */
sealed interface LocateAgeBucket {
    data class Minutes(val minutes: Int) : LocateAgeBucket
    data class Hours(val hours: Int) : LocateAgeBucket
    data class Days(val days: Int) : LocateAgeBucket
}

/** Buckets an age into the compact label unit: minutes under 1 h, hours through 96 h, then days. */
fun locateAgeBucket(ageMs: Long): LocateAgeBucket = when {
    ageMs < 60 * 60_000L -> LocateAgeBucket.Minutes((ageMs / 60_000L).toInt().coerceAtLeast(0))
    ageMs <= 96 * 60 * 60_000L -> LocateAgeBucket.Hours((ageMs / 3_600_000L).toInt())
    else -> LocateAgeBucket.Days((ageMs / 86_400_000L).toInt())
}

/** Whether a locate freshness label should render in the warning color: strictly older than 2 h. */
fun locateAgeWarn(ageMs: Long): Boolean = ageMs > LOCATE_AGE_WARN_MS

/** One row in the "Sharing with" list: a person and the latest time my share to them lasts. */
data class OutgoingShareRow(
    /** Identity domain string (OdinId.domainName) — stable list key and stop target. */
    val odinId: String,
    val name: String,
    val avatarInitials: String,
    /** Longest end-time across this person's overlapping shares (UTC epoch-ms). */
    val untilMs: Long,
)

/** One row in the "Sharing with you" list: a person and how stale their last fix is. */
data class IncomingShareRow(
    val odinId: String,
    val name: String,
    val avatarInitials: String,
    /** Age of their last received fix (ms); the label only shows past AGE_LABEL_AFTER_MS. */
    val ageMs: Long,
)

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
    /** Emergency screen entered/left: runs the per-contact verify loop only while visible. */
    data class SetEmergencyScreenVisible(val visible: Boolean) : LocationUiAction

    /** Tile home entered/resumed: reconfirm only the contacts already known to be stale. */
    data object TileHomeVisible : LocationUiAction

    /** Revoke an emergency-circle grant (real or still-pending) for [odinId]. */
    data class RemoveEmergencyContact(val odinId: String) : LocationUiAction

    data object RequestWhileInUseClicked : LocationUiAction
    data object RequestAlwaysClicked : LocationUiAction
    data object OpenSystemSettingsClicked : LocationUiAction

    /** Confirm on the emergency locate panel: send the request notice (embargoed 24h when
     *  [ambush]) and fetch [windowHours] of the peer's history over the temporal API. */
    data class ConfirmEmergencyLocate(
        val odinId: String,
        val name: String,
        val explanation: String,
        val windowHours: Int,
        val ambush: Boolean,
    ) : LocationUiAction
}

sealed interface LocationUiEvent {
    data object Activated : LocationUiEvent
    data object CloseOnboarding : LocationUiEvent

    /** Emergency retrieval succeeded — open the history viewer in peer mode. */
    data class OpenPeerHistory(val peerDomain: String, val peerName: String) : LocationUiEvent

    /** Emergency retrieval failed (the request notice was still sent) — snackbar. */
    data object LocateFetchFailed : LocationUiEvent

    /** An add/remove call against the emergency-location circle failed — snackbar. */
    data object EmergencyContactActionFailed : LocationUiEvent
}
