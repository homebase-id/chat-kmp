package id.homebase.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Locks down the conversation -> (messageId, summaryId) derivation used to
 * cancel a single conversation's notifications. The bug this guards against:
 * tapping any notification ran NotificationManager.cancelAll(), wiping every
 * conversation's notifications. The fix cancels only the tapped conversation's
 * two ids — so distinct conversations MUST yield disjoint id pairs, and the
 * derivation MUST match what the Android displayer actually posted.
 */
class ConversationNotificationIdsTest {

    private val a = Uuid.random().toString()
    private val b = Uuid.random().toString()

    /** Per-message id matches the display path (RichNotificationData.conversationNotificationId, unmasked). */
    @Test
    fun messageId_matchesUnmaskedHashCode() {
        val (messageId, _) = conversationNotificationIds(a)
        assertEquals(a.hashCode(), messageId)
    }

    /** Summary id matches RichNotificationDisplayer.postSummaryNotification's formula. */
    @Test
    fun summaryId_matchesDisplayerFormula() {
        val (_, summaryId) = conversationNotificationIds(a)
        assertEquals(SUMMARY_ID_OFFSET + (a.hashCode() and 0x7FFFFFFF), summaryId)
    }

    /** Bug #1 proof: a scoped cancel of A can never collide with B's notifications. */
    @Test
    fun differentConversations_produceDisjointIds() {
        val (msgA, summaryA) = conversationNotificationIds(a)
        val (msgB, summaryB) = conversationNotificationIds(b)

        val idsA = setOf(msgA, summaryA)
        val idsB = setOf(msgB, summaryB)

        assertTrue(idsA.intersect(idsB).isEmpty(), "conversation A and B share a notification id")
    }

    @Test
    fun summaryId_isAlwaysPositive() {
        repeat(50) {
            val (_, summaryId) = conversationNotificationIds(Uuid.random().toString())
            assertTrue(summaryId >= SUMMARY_ID_OFFSET, "summary id underflowed the offset")
        }
    }
}
