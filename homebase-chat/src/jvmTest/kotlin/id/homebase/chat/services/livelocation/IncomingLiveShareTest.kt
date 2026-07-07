package id.homebase.chat.services.livelocation

import id.homebase.api.client.liverelay.LiveLocationPoint
import id.homebase.api.common.OdinId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the incoming-share predicates (issue #1012). The receive side has no share-window deadline —
 * only each point's receipt time — so "is X sharing live now" is decided purely by freshness against
 * [INCOMING_SHARE_STALE_MS], and the synthetic `untilMs` is `receivedAtMs + staleMs`.
 */
class IncomingLiveShareTest {

    private val now = 1_000_000L
    private val stale = INCOMING_SHARE_STALE_MS

    private fun pos(vararg entries: Pair<String, Long>): Map<OdinId, LivePosition> =
        entries.associate { (domain, receivedAt) ->
            val id = OdinId(domain)
            id to LivePosition(id, LiveLocationPoint(lat = 1.0, lon = 2.0, ts = receivedAt), receivedAt)
        }

    // --- Global (chat-overview top-bar pin) ---

    @Test
    fun anyReturnsLatestSyntheticDeadlineAcrossFreshSenders() {
        val positions = pos("alice.com" to now - 10_000, "bob.com" to now - 1_000)
        // bob's point is freshest → its receivedAt + stale is the latest deadline.
        assertEquals(now - 1_000 + stale, incomingLiveShareAnyUntilMs(positions, stale, now))
    }

    @Test
    fun anyNullWhenEmpty() {
        assertNull(incomingLiveShareAnyUntilMs(emptyMap(), stale, now))
    }

    @Test
    fun anyNullWhenAllStale() {
        val positions = pos("alice.com" to now - stale - 1)
        assertNull(incomingLiveShareAnyUntilMs(positions, stale, now))
    }

    @Test
    fun anyFreshnessBoundaryIsInclusive() {
        // Exactly at the staleness edge still counts as fresh (age == stale).
        val positions = pos("alice.com" to now - stale)
        assertEquals(now, incomingLiveShareAnyUntilMs(positions, stale, now))
    }

    // --- Scoped (in-chat / details pin) ---

    @Test
    fun scopedIgnoresNonParticipants() {
        val positions = pos("carol.com" to now - 1_000)
        assertNull(incomingLiveShareUntilMs(positions, listOf(OdinId("alice.com")), stale, now))
    }

    @Test
    fun scopedReturnsLatestAmongParticipants() {
        val positions = pos("alice.com" to now - 30_000, "bob.com" to now - 5_000)
        val participants = listOf(OdinId("alice.com"), OdinId("bob.com"))
        assertEquals(now - 5_000 + stale, incomingLiveShareUntilMs(positions, participants, stale, now))
    }

    @Test
    fun scopedNullWhenParticipantsEmpty() {
        val positions = pos("alice.com" to now - 1_000)
        assertNull(incomingLiveShareUntilMs(positions, emptyList(), stale, now))
    }

    @Test
    fun scopedNullWhenParticipantPointStale() {
        val positions = pos("alice.com" to now - stale - 1)
        assertNull(incomingLiveShareUntilMs(positions, listOf(OdinId("alice.com")), stale, now))
    }
}
