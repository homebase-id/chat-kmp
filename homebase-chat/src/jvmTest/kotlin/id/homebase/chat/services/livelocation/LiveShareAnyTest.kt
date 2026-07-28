package id.homebase.chat.services.livelocation

import id.homebase.api.client.liverelay.TimedRecipient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins [liveShareAnyUntilMs] — the #816 sharing-pin predicate. ANY-coverage: one active entry to
 * a matching recipient suffices, the LATEST expiry wins (contrast [liveShareCoverageUntilMs]:
 * every recipient covered, the EARLIEST full-coverage end wins).
 */
class LiveShareAnyTest {

    private val now = 100_000L

    @Test
    fun globalReturnsLatestExpiryAcrossAllEntries() {
        val roster = listOf(
            TimedRecipient("alice.com", endTimeMs = now + 30_000),
            TimedRecipient("bob.com", endTimeMs = now + 60_000),
        )
        assertEquals(now + 60_000, liveShareAnyUntilMs(roster, null, now))
    }

    @Test
    fun globalNullWhenRosterEmpty() {
        assertNull(liveShareAnyUntilMs(emptyList(), null, now))
    }

    @Test
    fun globalNullWhenAllEntriesExpired() {
        val roster = listOf(TimedRecipient("alice.com", endTimeMs = now - 1))
        assertNull(liveShareAnyUntilMs(roster, null, now))
    }

    @Test
    fun scopedAnyOneActiveParticipantSuffices() {
        // Sharing with only ONE of two conversation participants: the pin shows (ANY)…
        val roster = listOf(TimedRecipient("alice.com", endTimeMs = now + 60_000))
        val participants = listOf("alice.com", "bob.com")
        assertEquals(now + 60_000, liveShareAnyUntilMs(roster, participants, now))
        // …while the FULL-coverage predicate (offer suppression) correctly says "not covered".
        assertNull(liveShareCoverageUntilMs(roster, participants, now))
    }

    @Test
    fun scopedReturnsLatestAmongParticipants() {
        // Two covered participants: ANY reports the LATEST end (coverage would report the earliest).
        val roster = listOf(
            TimedRecipient("alice.com", endTimeMs = now + 30_000),
            TimedRecipient("bob.com", endTimeMs = now + 60_000),
        )
        assertEquals(now + 60_000, liveShareAnyUntilMs(roster, listOf("alice.com", "bob.com"), now))
        assertEquals(now + 30_000, liveShareCoverageUntilMs(roster, listOf("alice.com", "bob.com"), now))
    }

    @Test
    fun scopedIgnoresNonParticipantEntries() {
        val roster = listOf(TimedRecipient("carol.com", endTimeMs = now + 60_000))
        assertNull(liveShareAnyUntilMs(roster, listOf("alice.com"), now))
    }

    @Test
    fun scopedNullWhenNoParticipantActive() {
        val roster = listOf(
            TimedRecipient("alice.com", endTimeMs = now - 1), // expired participant entry
            TimedRecipient("carol.com", endTimeMs = now + 60_000), // active non-participant
        )
        assertNull(liveShareAnyUntilMs(roster, listOf("alice.com"), now))
    }

    @Test
    fun scopedEmptyRecipientListReturnsNull() {
        val roster = listOf(TimedRecipient("alice.com", endTimeMs = now + 60_000))
        assertNull(liveShareAnyUntilMs(roster, emptyList(), now))
    }

    @Test
    fun duplicatePerRecipientEntriesLatestWins() {
        val roster = listOf(
            TimedRecipient("alice.com", endTimeMs = now + 10_000),
            TimedRecipient("alice.com", endTimeMs = now + 90_000),
        )
        assertEquals(now + 90_000, liveShareAnyUntilMs(roster, listOf("alice.com"), now))
    }
}
