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
    /** Contacts we can locate (the `iCanLocate` app-data flag) — the "who you can locate" list. */
    val whoICanLocate: List<ContactUiModel> = emptyList(),
    /** False until the locatable-contacts list has loaded at least once (drives the spinner). */
    val whoICanLocateLoaded: Boolean = false,
    /** Per-entry temporal-verify status for the "who I can locate" list, keyed by odinId.domainName.
     *  Absent key = not yet requested (renders nothing until the section is expanded). */
    val whoICanLocateStatus: Map<String, LocateVerifyStatus> = emptyMap(),
    /** Owner-console deep link to manage the Emergency Location Access circle (the actual location
     *  drive grant); null until the identity is known. */
    val emergencyManageUrl: String? = null,
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

/**
 * Result of the per-entry temporal-access preflight for a "who I can locate" row. Expand-triggered
 * verifies show a spinner (visible feedback that the check is running); the periodic follow-up
 * passes while the section stays open are silent (the old value stays visible until the new result
 * lands, so the list doesn't flash spinners every minute). Only a successful, data-bearing
 * [Active] carries the [LOCATE_VERIFY_TTL_MS] cache ("they sent data back within 60s", #950);
 * [Broken], [Unreachable], and a no-data [Active] re-verify on every pass so an error state
 * clears the moment access/data returns instead of sticking across collapse/expand (#985).
 */
sealed interface LocateVerifyStatus {
    /** Preflight in flight with the spinner requested (expand-triggered, or no prior result). */
    data object Loading : LocateVerifyStatus

    /** A completed verify; [verifiedAtMs] is when the result landed (epoch ms), for the TTL. */
    sealed interface Resolved : LocateVerifyStatus {
        val verifiedAtMs: Long
    }

    /** Verify succeeded but the peer no longer grants us access → broken-link icon. */
    data class Broken(override val verifiedAtMs: Long) : Resolved

    /** We hold access; [newestModifiedMs] is the peer's newest-file time, or null when no data yet. */
    data class Active(val newestModifiedMs: Long?, override val verifiedAtMs: Long) : Resolved

    /**
     * The verify threw (network/parse failure) — the peer's server is unreachable and access is
     * unknown → disconnected icon. Inconclusive, so it must never look like [Broken]; like every
     * error result it is re-verified on the next pass (#985) instead of blanking the row.
     */
    data class Unreachable(override val verifiedAtMs: Long) : Resolved
}

/** Freshness window for a successful, DATA-BEARING locate-verify result: re-expands inside it
 *  reuse the cache. Error/no-data results never cache (#985). */
const val LOCATE_VERIFY_TTL_MS = 60_000L

/** Age past which a locate row's freshness label renders in the warning (orange) color (#879). */
const val LOCATE_AGE_WARN_MS = 2 * 60 * 60_000L

/** How long a locate verify waits for the app to come online before attempting the POST, so an
 *  expand right after cold start spins instead of flashing broken clouds (#998). */
const val LOCATE_VERIFY_ONLINE_WAIT_MS = 15_000L

/**
 * Whether a verify pass over "Who you can locate" should (re-)issue the temporal verify for a row
 * in this state: never while one is in flight; a successful DATA-BEARING [LocateVerifyStatus.Active]
 * younger than [LOCATE_VERIFY_TTL_MS] is reused (the #950 "they sent data back within 60s" cache);
 * everything else — [LocateVerifyStatus.Broken], [LocateVerifyStatus.Unreachable], a no-data
 * Active, stale, or never verified — re-verifies, so an error state never sticks across
 * collapse/expand (#985).
 */
fun LocateVerifyStatus?.needsReverify(nowMs: Long): Boolean = when (this) {
    LocateVerifyStatus.Loading -> false
    // Only a successful, data-bearing result earned the cache. A no-data Active re-verifies so
    // a peer whose GPS just started reporting shows up on the next expand.
    is LocateVerifyStatus.Active ->
        newestModifiedMs == null || nowMs - verifiedAtMs >= LOCATE_VERIFY_TTL_MS
    // Broken / Unreachable: an error result never caches — re-verify on every pass so a broken
    // cloud clears the moment access/data returns (#985).
    is LocateVerifyStatus.Resolved -> true
    null -> true
}

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
    /** "Who you can locate" section expanded/collapsed. Expanded starts the periodic per-entry
     *  link-freshness verify loop (immediate first pass, then every [LOCATE_VERIFY_TTL_MS]);
     *  collapsed cancels it. */
    data class SetLocatableExpanded(val expanded: Boolean) : LocationUiAction
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
}
