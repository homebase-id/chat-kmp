package id.homebase.chat.conversationlist

import id.homebase.core.notifications.PendingNotificationTap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.Uuid

/**
 * Locks down the resolution policy used by the VM's
 * pendingNotificationTap collector:
 *  - no tap → nothing to do
 *  - tap present, conversation absent → still wait (next sync emit
 *    will try again, and the VM's fast-path kick will have triggered
 *    a direct DB load by then if the row exists locally)
 *  - tap present, conversation present → hand back the tap so the
 *    caller fires selectConversation and clears
 *
 * The previous `dataReady` gate is gone: the fast-path collector
 * forces a direct DB load for the tap's conversation as soon as the
 * tap is set, so the conversation showing up in `items` is itself
 * proof that local data exists — `dataReady` would only add latency.
 */
@OptIn(ExperimentalTime::class)
class ConversationListPendingTapResolutionTest {

    private val convoA = Uuid.random()
    private val convoB = Uuid.random()
    private val msgA = Uuid.random()

    private fun tap(conversationId: Uuid = convoA, messageId: Uuid = msgA) =
        PendingNotificationTap.Tap(conversationId, messageId, Clock.System.now())

    @Test
    fun nullTap_returnsNull() {
        assertNull(
            resolveNotificationTap(
                tap = null,
                conversationIds = setOf(convoA),
            )
        )
    }

    @Test
    fun conversationAbsent_returnsNull() {
        // Conversation hasn't been loaded into items yet — keep waiting.
        assertNull(
            resolveNotificationTap(
                tap = tap(),
                conversationIds = setOf(convoB),
            )
        )
    }

    @Test
    fun conversationPresent_returnsTap() {
        val t = tap()
        val result = resolveNotificationTap(
            tap = t,
            conversationIds = setOf(convoA, convoB),
        )
        assertEquals(t, result)
    }

    @Test
    fun conversationPresent_resolvesEvenBeforeDataReady() {
        // The fast-path DB lookup can insert the conversation into
        // items before ConversationStream.start() has finished its
        // enrichment passes. The resolver must not block on dataReady
        // in that window — if items contains the id, navigate.
        val t = tap()
        val result = resolveNotificationTap(
            tap = t,
            conversationIds = setOf(convoA),
        )
        assertEquals(t, result)
    }

    @Test
    fun emptyConversationSet_returnsNull() {
        assertNull(
            resolveNotificationTap(
                tap = tap(),
                conversationIds = emptySet(),
            )
        )
    }
}
