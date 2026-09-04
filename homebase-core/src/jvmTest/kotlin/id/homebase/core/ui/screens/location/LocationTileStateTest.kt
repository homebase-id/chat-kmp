package id.homebase.core.ui.screens.location

import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
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

    @Test
    fun emergencyOffOnlyWhenAllListsEmpty() {
        assertFalse(deriveLocationTiles(LocationUiState()).emergencyOn)
        assertTrue(deriveLocationTiles(LocationUiState(whoCanLocateMe = listOf(alice))).emergencyOn)
        assertTrue(deriveLocationTiles(LocationUiState(whoCanLocateMePending = listOf(alice))).emergencyOn)
        assertTrue(deriveLocationTiles(LocationUiState(whoICanLocate = listOf(alice))).emergencyOn)
    }

    @Test
    fun canLocateMeCountDedupsRealAndPending() {
        val tiles = deriveLocationTiles(
            LocationUiState(whoCanLocateMe = listOf(alice), whoCanLocateMePending = listOf(alice, bob)),
        )
        assertEquals(2, tiles.canLocateMeCount)
    }

    @Test
    fun historyFollowsTrackingSwitch() {
        assertFalse(deriveLocationTiles(LocationUiState(allowLocationHistory = false)).historyOn)
        assertTrue(deriveLocationTiles(LocationUiState(allowLocationHistory = true)).historyOn)
    }

    @Test
    fun liveOnForEitherDirection() {
        val outgoing = OutgoingShareRow("bob.example", "Bob", "B", untilMs = 1L)
        val incoming = IncomingShareRow("bob.example", "Bob", "B", ageMs = 0L)
        assertFalse(deriveLocationTiles(LocationUiState()).liveOn)
        assertTrue(deriveLocationTiles(LocationUiState(outgoingShares = listOf(outgoing))).liveOn)
        assertTrue(deriveLocationTiles(LocationUiState(incomingShares = listOf(incoming))).liveOn)
    }

    @Test
    fun settingsOnForViewersAndFullGrants() {
        assertTrue(deriveLocationTiles(LocationUiState(trackingAvailable = false)).settingsOn)
        assertTrue(
            deriveLocationTiles(
                LocationUiState(trackingAvailable = true, whileInUseGranted = true, alwaysGranted = true),
            ).settingsOn,
        )
        assertFalse(
            deriveLocationTiles(
                LocationUiState(trackingAvailable = true, whileInUseGranted = true, alwaysGranted = false),
            ).settingsOn,
        )
    }

    @Test
    fun staleCountMirrorsState() {
        assertEquals(3, deriveLocationTiles(LocationUiState(staleLocatableCount = 3)).staleCount)
    }
}
