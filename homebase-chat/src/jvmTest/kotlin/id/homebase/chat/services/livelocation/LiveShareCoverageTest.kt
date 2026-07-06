package id.homebase.chat.services.livelocation

import id.homebase.api.client.liverelay.TimedRecipient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins [liveShareCoverageUntilMs]: the "am I already live-sharing to this conversation" check
 * that hides the per-bubble share offers (#966 follow-up). Coverage requires EVERY recipient to
 * have an active roster entry; the coverage horizon is the minimum across recipients of each
 * recipient's latest entry.
 */
class LiveShareCoverageTest {

    private val now = 100_000L

    @Test
    fun fullCoverageReturnsTheEarliestExpiry() {
        val roster = listOf(
            TimedRecipient("alice.com", endTimeMs = now + 60_000),
            TimedRecipient("bob.com", endTimeMs = now + 30_000),
        )
        assertEquals(
            now + 30_000,
            liveShareCoverageUntilMs(roster, listOf("alice.com", "bob.com"), now),
        )
    }

    @Test
    fun partialCoverageReturnsNull() {
        val roster = listOf(TimedRecipient("alice.com", endTimeMs = now + 60_000))
        assertNull(liveShareCoverageUntilMs(roster, listOf("alice.com", "bob.com"), now))
    }

    @Test
    fun expiredEntriesDoNotCount() {
        val roster = listOf(TimedRecipient("alice.com", endTimeMs = now - 1))
        assertNull(liveShareCoverageUntilMs(roster, listOf("alice.com"), now))
    }

    @Test
    fun perRecipientLatestEntryWins() {
        // Duplicate entries per recipient are legal (roster is additive); the newest one counts.
        val roster = listOf(
            TimedRecipient("alice.com", endTimeMs = now + 10_000),
            TimedRecipient("alice.com", endTimeMs = now + 90_000),
        )
        assertEquals(now + 90_000, liveShareCoverageUntilMs(roster, listOf("alice.com"), now))
    }

    @Test
    fun unrelatedRosterEntriesAreIgnored() {
        val roster = listOf(TimedRecipient("carol.com", endTimeMs = now + 60_000))
        assertNull(liveShareCoverageUntilMs(roster, listOf("alice.com"), now))
    }

    @Test
    fun emptyRecipientsReturnsNull() {
        val roster = listOf(TimedRecipient("alice.com", endTimeMs = now + 60_000))
        assertNull(liveShareCoverageUntilMs(roster, emptyList(), now))
    }
}
