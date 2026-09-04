package id.homebase.core.ui.screens.location

import id.homebase.core.contactbook.LOCATE_STALE_WARN_MS
import id.homebase.core.ui.screens.location.devices.LocationDeviceInfo

data class LocationTiles(
    val emergencyOn: Boolean,
    val canLocateMeCount: Int,
    val canLocateCount: Int,
    val staleCount: Int,
    val historyOn: Boolean,
    val pointsToday: Int,
    val deviceCount: Int,
    /** The device with the freshest recent fix when THIS device isn't tracking (viewer devices,
     *  or a phone with its switch off while another phone records) — null when this device tracks. */
    val trackedDevice: LocationDeviceInfo?,
    val liveOn: Boolean,
    val outgoingCount: Int,
    val incomingCount: Int,
    val settingsOn: Boolean,
)

fun deriveLocationTiles(s: LocationUiState, nowMs: Long): LocationTiles {
    val canLocateMe = (s.whoCanLocateMe + s.whoCanLocateMePending).distinctBy { it.odinId }.size
    // History is "on" whenever any device of this identity reported within the stale window —
    // read from the local index, no server call — not only when this device's switch is on.
    val recentDevice = s.devices
        .filter { device -> device.lastFix?.let { nowMs - it.t <= LOCATE_STALE_WARN_MS } == true }
        .maxByOrNull { it.lastFix!!.t }
    return LocationTiles(
        emergencyOn = canLocateMe > 0 || s.whoICanLocate.isNotEmpty(),
        canLocateMeCount = canLocateMe,
        canLocateCount = s.whoICanLocate.size,
        staleCount = s.staleLocatableCount,
        historyOn = s.allowLocationHistory || recentDevice != null,
        // This device's own count only covers its own hour files; the day traces cover every device.
        pointsToday = maxOf(s.pointsToday, s.todayTraces.sumOf { it.pointCount }),
        deviceCount = s.devices.size,
        trackedDevice = recentDevice.takeIf { !s.allowLocationHistory },
        liveOn = s.outgoingShares.isNotEmpty() || s.incomingShares.isNotEmpty(),
        outgoingCount = s.outgoingShares.size,
        incomingCount = s.incomingShares.size,
        // Viewer devices never request permissions, so their settings are always complete.
        settingsOn = !s.trackingAvailable || (s.whileInUseGranted && s.alwaysGranted),
    )
}
