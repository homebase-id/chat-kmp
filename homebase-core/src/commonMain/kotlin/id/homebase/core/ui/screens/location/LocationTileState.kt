package id.homebase.core.ui.screens.location

data class LocationTiles(
    val emergencyOn: Boolean,
    val canLocateMeCount: Int,
    val canLocateCount: Int,
    val staleCount: Int,
    val historyOn: Boolean,
    val pointsToday: Int,
    val deviceCount: Int,
    val liveOn: Boolean,
    val outgoingCount: Int,
    val incomingCount: Int,
    val settingsOn: Boolean,
)

fun deriveLocationTiles(s: LocationUiState): LocationTiles {
    val canLocateMe = (s.whoCanLocateMe + s.whoCanLocateMePending).distinctBy { it.odinId }.size
    return LocationTiles(
        emergencyOn = canLocateMe > 0 || s.whoICanLocate.isNotEmpty(),
        canLocateMeCount = canLocateMe,
        canLocateCount = s.whoICanLocate.size,
        staleCount = s.staleLocatableCount,
        historyOn = s.allowLocationHistory,
        pointsToday = s.pointsToday,
        deviceCount = s.devices.size,
        liveOn = s.outgoingShares.isNotEmpty() || s.incomingShares.isNotEmpty(),
        outgoingCount = s.outgoingShares.size,
        incomingCount = s.incomingShares.size,
        // Viewer devices never request permissions, so their settings are always complete.
        settingsOn = !s.trackingAvailable || (s.whileInUseGranted && s.alwaysGranted),
    )
}
