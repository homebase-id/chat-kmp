package id.homebase.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Locks down the per-conversation notification counter that drives the
 * "$n new messages" summary body. The bug this guards against: the count was
 * only reset on conversation-open / mark-as-read / logout, never on a
 * notification tap or swipe-dismiss, so a tapped-away conversation kept its
 * stale count and the next incoming message read "$n+1 new messages" instead
 * of restarting at 1.
 */
class ConversationNotificationCountsTest {

    private val a = Uuid.random().toString()
    private val b = Uuid.random().toString()

    @Test
    fun increment_returnsRunningCount() {
        val counts = ConversationNotificationCounts()
        assertEquals(1, counts.increment(a))
        assertEquals(2, counts.increment(a))
        assertEquals(3, counts.increment(a))
    }

    @Test
    fun peek_returnsZeroForUnknownConversation() {
        val counts = ConversationNotificationCounts()
        assertEquals(0, counts.peek(a))
    }

    @Test
    fun peek_reflectsLastIncrement() {
        val counts = ConversationNotificationCounts()
        counts.increment(a)
        counts.increment(a)
        assertEquals(2, counts.peek(a))
    }

    /** Bug #2: after a tap/open clears the conversation, the next message restarts at 1. */
    @Test
    fun clear_restartsConversationAtOne() {
        val counts = ConversationNotificationCounts()
        repeat(5) { counts.increment(a) }
        assertEquals(5, counts.peek(a))

        counts.clear(a)
        assertEquals(0, counts.peek(a))
        assertEquals(1, counts.increment(a))
    }

    /** Clearing one conversation must not touch another (mirror of Bug #1's intent). */
    @Test
    fun clear_isScopedToOneConversation() {
        val counts = ConversationNotificationCounts()
        repeat(3) { counts.increment(a) }
        repeat(2) { counts.increment(b) }

        counts.clear(a)

        assertEquals(0, counts.peek(a))
        assertEquals(2, counts.peek(b))
    }

    @Test
    fun clearAll_wipesEverything() {
        val counts = ConversationNotificationCounts()
        counts.increment(a)
        counts.increment(b)

        counts.clearAll()

        assertEquals(0, counts.peek(a))
        assertEquals(0, counts.peek(b))
    }
}
