package id.homebase.api.client.liverelay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveShareRosterTest {

    @Test
    fun add_appendsNewEntries() {
        val out = LiveShareRoster.add(current = emptyList(), add = listOf("a", "b"), endTimeMs = 100, nowMs = 0)
        assertEquals(listOf(TimedRecipient("a", 100), TimedRecipient("b", 100)), out)
    }

    @Test
    fun add_sameRecipientTwice_keepsBothEntriesWithDistinctEndTimes() {
        // "a" shared until t=100; a second share to "a" until t=300 -> TWO entries (individually
        // removable), not collapsed.
        val first = LiveShareRoster.add(emptyList(), listOf("a"), endTimeMs = 100, nowMs = 0)
        val out = LiveShareRoster.add(first, listOf("a"), endTimeMs = 300, nowMs = 50)
        assertEquals(listOf(TimedRecipient("a", 100), TimedRecipient("a", 300)), out)
    }

    @Test
    fun liveRecipientIds_dedupsToUniqueIdentities() {
        // The same identity appears in three live entries -> sent to exactly once.
        val roster = listOf(
            TimedRecipient("a", 100),
            TimedRecipient("a", 300),
            TimedRecipient("b", 200),
        )
        assertEquals(listOf("a", "b"), LiveShareRoster.liveRecipientIds(roster, nowMs = 50))
    }

    @Test
    fun add_overlappingShares_keepEntries_butSendUnions() {
        // Share 1: {a,b} until 100. Share 2: {b,c} until 100.
        val share1 = LiveShareRoster.add(emptyList(), listOf("a", "b"), endTimeMs = 100, nowMs = 0)
        val share2 = LiveShareRoster.add(share1, listOf("b", "c"), endTimeMs = 100, nowMs = 0)
        // "b" appears twice in the roster (one entry per share)...
        assertEquals(4, share2.size)
        assertEquals(2, share2.count { it.odinId == "b" })
        // ...but is fanned out to once.
        assertEquals(setOf("a", "b", "c"), LiveShareRoster.liveRecipientIds(share2, nowMs = 0).toSet())
        assertEquals(3, LiveShareRoster.liveRecipientIds(share2, nowMs = 0).size)
    }

    @Test
    fun add_dropsAlreadyExpiredEntries() {
        val current = listOf(TimedRecipient("old", 50), TimedRecipient("keep", 500))
        // now=100 -> "old" expired and is dropped; adding "new" until 600.
        val out = LiveShareRoster.add(current = current, add = listOf("new"), endTimeMs = 600, nowMs = 100)
        assertEquals(listOf(TimedRecipient("keep", 500), TimedRecipient("new", 600)), out)
    }

    @Test
    fun live_filtersExpired() {
        val roster = listOf(TimedRecipient("a", 100), TimedRecipient("b", 50))
        assertEquals(listOf(TimedRecipient("a", 100)), LiveShareRoster.live(roster, nowMs = 75))
        assertTrue(LiveShareRoster.live(roster, nowMs = 200).isEmpty())
    }

    @Test
    fun remove_dropsOnlyTheMatchingShare() {
        // Two overlapping shares sharing recipient "b": share1 {a,b}@100, share2 {b,c}@300.
        val share1 = LiveShareRoster.add(emptyList(), listOf("a", "b"), endTimeMs = 100, nowMs = 0)
        val roster = LiveShareRoster.add(share1, listOf("b", "c"), endTimeMs = 300, nowMs = 0)
        // Stop share1 ({a,b}@100): only its entries drop; share2's "b"@300 and "c"@300 stay.
        val out = LiveShareRoster.remove(roster, recipients = listOf("a", "b"), endTimeMs = 100)
        assertEquals(listOf(TimedRecipient("b", 300), TimedRecipient("c", 300)), out)
    }

    @Test
    fun remove_matchesOnBothRecipientAndEndTime() {
        val roster = listOf(TimedRecipient("a", 100), TimedRecipient("a", 300))
        // Right identity, wrong end-time -> no-op; the other "a" entry survives.
        assertEquals(roster, LiveShareRoster.remove(roster, recipients = listOf("a"), endTimeMs = 200))
        // Exact {recipient, end-time} match removes just that one.
        assertEquals(
            listOf(TimedRecipient("a", 300)),
            LiveShareRoster.remove(roster, recipients = listOf("a"), endTimeMs = 100),
        )
    }
}
