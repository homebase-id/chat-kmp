package id.homebase.chat.services.livelocation

import id.homebase.api.client.liverelay.LiveLocationPoint
import id.homebase.api.client.liverelay.TimedRecipient
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

    // --- Helpers ---

    @Test
    fun quantizeRoundsUpToBucketAndPassesNull() {
        val q = INCOMING_SHARE_QUANTUM_MS
        assertNull(quantizeLiveShareDeadlineUp(null, q))
        assertEquals(q, quantizeLiveShareDeadlineUp(1, q))          // rounds up off-boundary
        assertEquals(q, quantizeLiveShareDeadlineUp(q, q))          // exact boundary unchanged
        assertEquals(2 * q, quantizeLiveShareDeadlineUp(q + 1, q))  // never rounds into the past
    }

    @Test
    fun pinUntilIsLaterOfOwnAndIncomingOrNull() {
        assertNull(liveSharePinUntilMs(null, null))
        assertEquals(50L, liveSharePinUntilMs(50L, null))
        assertEquals(50L, liveSharePinUntilMs(null, 50L))
        assertEquals(80L, liveSharePinUntilMs(30L, 80L))
    }

    // --- Composed per-surface helpers (the pins' single source of truth) ---

    @Test
    fun conversationPinLightsForEitherDirectionAndScopesToParticipants() {
        val alice = OdinId("alice.com")
        val roster = listOf(TimedRecipient("alice.com", endTimeMs = now + 30_000))
        val positions = pos("alice.com" to now - 1_000, "carol.com" to now) // carol not a participant

        // Outgoing only: my roster entry wins when incoming is absent.
        assertEquals(
            now + 30_000,
            conversationLiveSharePinUntilMs(roster, emptyMap(), listOf(alice), now),
        )
        // Incoming only: quantized freshness deadline.
        assertEquals(
            quantizeLiveShareDeadlineUp(now - 1_000 + stale),
            conversationLiveSharePinUntilMs(emptyList(), positions, listOf(alice), now),
        )
        // Both: the later (incoming, 1h) wins over outgoing (30s). Non-participant carol is ignored.
        assertEquals(
            quantizeLiveShareDeadlineUp(now - 1_000 + stale),
            conversationLiveSharePinUntilMs(roster, positions, listOf(alice), now),
        )
        // Neither.
        assertNull(conversationLiveSharePinUntilMs(emptyList(), emptyMap(), listOf(alice), now))
    }

    @Test
    fun globalPinLightsForEitherDirectionUnscoped() {
        val roster = listOf(TimedRecipient("bob.com", endTimeMs = now + 30_000))
        val positions = pos("carol.com" to now - 1_000) // any sender counts, no participant filter

        assertEquals(now + 30_000, globalLiveSharePinUntilMs(roster, emptyMap(), now))
        assertEquals(
            quantizeLiveShareDeadlineUp(now - 1_000 + stale),
            globalLiveSharePinUntilMs(emptyList(), positions, now),
        )
        assertNull(globalLiveSharePinUntilMs(emptyList(), emptyMap(), now))
    }
}
