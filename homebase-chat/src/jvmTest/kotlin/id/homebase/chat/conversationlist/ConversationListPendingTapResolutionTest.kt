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
 *  - tap present but conversations not yet ready → wait
 *  - tap present, ready, conversation absent → still wait (next sync
 *    emit will try again)
 *  - tap present, ready, conversation present → hand back the tap so
 *    the caller fires selectConversation and clears
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
                dataReady = true,
                conversationIds = setOf(convoA),
            )
        )
    }

    @Test
    fun notDataReady_returnsNull() {
        assertNull(
            resolveNotificationTap(
                tap = tap(),
                dataReady = false,
                conversationIds = setOf(convoA),
            )
        )
    }

    @Test
    fun conversationAbsent_returnsNull() {
        // Conversation hasn't synced yet — keep waiting.
        assertNull(
            resolveNotificationTap(
                tap = tap(),
                dataReady = true,
                conversationIds = setOf(convoB),
            )
        )
    }

    @Test
    fun conversationPresent_returnsTap() {
        val t = tap()
        val result = resolveNotificationTap(
            tap = t,
            dataReady = true,
            conversationIds = setOf(convoA, convoB),
        )
        assertEquals(t, result)
    }

    @Test
    fun emptyConversationSet_returnsNull() {
        assertNull(
            resolveNotificationTap(
                tap = tap(),
                dataReady = true,
                conversationIds = emptySet(),
            )
        )
    }
}
