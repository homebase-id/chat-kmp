package id.homebase.core.ui.screens.location

import id.homebase.api.common.OdinId
import id.homebase.api.sync.database.BufferedLocationPoint
import id.homebase.chat.data.ContactUiModel
import id.homebase.core.contactbook.LOCATE_STALE_WARN_MS
import id.homebase.core.ui.screens.location.devices.LocationDeviceInfo
import id.homebase.core.ui.screens.location.history.DeviceTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class LocationTileStateTest {

    private fun contact(domain: String) = ContactUiModel(
        id = Uuid.random(),
        odinId = OdinId(domain),
        name = domain,
        avatarInitials = "AA",
    )

    private val alice = contact("alice.example")
    private val bob = contact("bob.example")
    private val now = 30L * 24 * 60 * 60_000L

    private fun point(t: Long) = BufferedLocationPoint(t = t, lat = 0.0, lon = 0.0, acc = null, src = "gps", fg = true)

    private fun device(name: String, lastFixMs: Long?, thisDevice: Boolean = false) = LocationDeviceInfo(
        deviceId = Uuid.random(),
        name = name,
        platform = "android",
        lastFix = lastFixMs?.let(::point),
        isThisDevice = thisDevice,
    )

    @Test
    fun emergencyOffOnlyWhenAllListsEmpty() {
        assertFalse(deriveLocationTiles(LocationUiState(), now).emergencyOn)
        assertTrue(deriveLocationTiles(LocationUiState(whoCanLocateMe = listOf(alice)), now).emergencyOn)
        assertTrue(deriveLocationTiles(LocationUiState(whoCanLocateMePending = listOf(alice)), now).emergencyOn)
        assertTrue(deriveLocationTiles(LocationUiState(whoICanLocate = listOf(alice)), now).emergencyOn)
    }

    @Test
    fun canLocateMeCountDedupsRealAndPending() {
        val tiles = deriveLocationTiles(LocationUiState(whoCanLocateMe = listOf(alice), whoCanLocateMePending = listOf(alice, bob)), now)
        assertEquals(2, tiles.canLocateMeCount)
    }

    @Test
    fun historyFollowsTrackingSwitch() {
        assertFalse(deriveLocationTiles(LocationUiState(allowLocationHistory = false), now).historyOn)
        assertTrue(deriveLocationTiles(LocationUiState(allowLocationHistory = true), now).historyOn)
    }

    @Test
    fun anotherDeviceReportingRecentlyTurnsHistoryOn() {
        val phone = device("Pixel", lastFixMs = now - 60_000L)
        val tiles = deriveLocationTiles(LocationUiState(allowLocationHistory = false, devices = listOf(phone)), now)
        assertTrue(tiles.historyOn)
        assertEquals(phone, tiles.trackedDevice)
    }

    @Test
    fun freshestDeviceWins() {
        val older = device("Tablet", lastFixMs = now - 3_600_000L)
        val newer = device("Pixel", lastFixMs = now - 60_000L)
        val tiles = deriveLocationTiles(LocationUiState(devices = listOf(older, newer)), now)
        assertEquals(newer, tiles.trackedDevice)
    }

    @Test
    fun staleDeviceFixKeepsHistoryOff() {
        val quiet = device("Pixel", lastFixMs = now - LOCATE_STALE_WARN_MS - 1)
        val tiles = deriveLocationTiles(LocationUiState(devices = listOf(quiet, device("Old", null))), now)
        assertFalse(tiles.historyOn)
        assertEquals(null, tiles.trackedDevice)
    }

    @Test
    fun thisDeviceTrackingReportsNoTrackedDevice() {
        val phone = device("Pixel", lastFixMs = now - 60_000L)
        val tiles = deriveLocationTiles(LocationUiState(allowLocationHistory = true, devices = listOf(phone)), now)
        assertTrue(tiles.historyOn)
        assertEquals(null, tiles.trackedDevice)
    }

    @Test
    fun pointsTodayCoversEveryDevice() {
        val trace = DeviceTrace(Uuid.random(), segments = listOf(listOf(point(now - 1), point(now - 2)), listOf(point(now - 3))))
        assertEquals(3, deriveLocationTiles(LocationUiState(pointsToday = 0, todayTraces = listOf(trace)), now).pointsToday)
        assertEquals(7, deriveLocationTiles(LocationUiState(pointsToday = 7, todayTraces = listOf(trace)), now).pointsToday)
    }

    @Test
    fun liveOnForEitherDirection() {
        val outgoing = OutgoingShareRow("bob.example", "Bob", "B", untilMs = 1L)
        val incoming = IncomingShareRow("bob.example", "Bob", "B", ageMs = 0L)
        assertFalse(deriveLocationTiles(LocationUiState(), now).liveOn)
        assertTrue(deriveLocationTiles(LocationUiState(outgoingShares = listOf(outgoing)), now).liveOn)
        assertTrue(deriveLocationTiles(LocationUiState(incomingShares = listOf(incoming)), now).liveOn)
    }

    @Test
    fun settingsOnForViewersAndFullGrants() {
        assertTrue(deriveLocationTiles(LocationUiState(trackingAvailable = false), now).settingsOn)
        assertTrue(
            deriveLocationTiles(LocationUiState(trackingAvailable = true, whileInUseGranted = true, alwaysGranted = true), now).settingsOn,
        )
        assertFalse(
            deriveLocationTiles(LocationUiState(trackingAvailable = true, whileInUseGranted = true, alwaysGranted = false), now).settingsOn,
        )
    }

    @Test
    fun staleCountMirrorsState() {
        assertEquals(3, deriveLocationTiles(LocationUiState(staleLocatableCount = 3), now).staleCount)
    }
}
