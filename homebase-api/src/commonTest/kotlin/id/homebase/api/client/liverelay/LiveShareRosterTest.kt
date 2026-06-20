package id.homebase.api.client.liverelay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveShareRosterTest {

    @Test
    fun merge_addsNewRecipientsWithEndTime() {
        val out = LiveShareRoster.merge(current = emptyList(), add = listOf("a", "b"), endTimeMs = 100, nowMs = 0)
        assertEquals(listOf(TimedRecipient("a", 100), TimedRecipient("b", 100)), out)
    }

    @Test
    fun merge_sameRecipientInTwoRequests_keepsLatestEndTime() {
        // "a" already shared until t=100; a second request shares until t=300 -> keep 300.
        val current = listOf(TimedRecipient("a", 100))
        val out = LiveShareRoster.merge(current = current, add = listOf("a"), endTimeMs = 300, nowMs = 50)
        assertEquals(listOf(TimedRecipient("a", 300)), out)
    }

    @Test
    fun merge_doesNotShortenAnExistingLongerWindow() {
        // Existing window (t=500) is longer than the new request (t=200) -> the longer one wins.
        val current = listOf(TimedRecipient("a", 500))
        val out = LiveShareRoster.merge(current = current, add = listOf("a"), endTimeMs = 200, nowMs = 0)
        assertEquals(listOf(TimedRecipient("a", 500)), out)
    }

    @Test
    fun merge_overlappingShares_unionsRecipients() {
        // Share 1: {a,b} until 100. Share 2: {b,c} until 100 -> union {a,b,c}, b not duplicated.
        val share1 = LiveShareRoster.merge(emptyList(), listOf("a", "b"), endTimeMs = 100, nowMs = 0)
        val share2 = LiveShareRoster.merge(share1, listOf("b", "c"), endTimeMs = 100, nowMs = 0)
        assertEquals(setOf("a", "b", "c"), share2.map { it.odinId }.toSet())
        assertEquals(3, share2.size)
    }

    @Test
    fun merge_dropsAlreadyExpiredCurrentEntries() {
        val current = listOf(TimedRecipient("old", 50), TimedRecipient("keep", 500))
        // now=100 -> "old" expired and is dropped; adding "new" until 600.
        val out = LiveShareRoster.merge(current = current, add = listOf("new"), endTimeMs = 600, nowMs = 100)
        assertEquals(setOf("keep", "new"), out.map { it.odinId }.toSet())
    }

    @Test
    fun live_filtersExpired() {
        val roster = listOf(TimedRecipient("a", 100), TimedRecipient("b", 50))
        assertEquals(listOf(TimedRecipient("a", 100)), LiveShareRoster.live(roster, nowMs = 75))
        assertTrue(LiveShareRoster.live(roster, nowMs = 200).isEmpty())
    }
}
