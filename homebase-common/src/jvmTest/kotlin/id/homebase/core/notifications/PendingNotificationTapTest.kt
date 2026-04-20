package id.homebase.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Locks down the PendingNotificationTap contract consumed by
 * NotificationService and ConversationListViewModel.
 */
class PendingNotificationTapTest {

    private val convoA = Uuid.random()
    private val convoB = Uuid.random()
    private val msgA = Uuid.random()
    private val msgB = Uuid.random()

    @Test
    fun initialState_isNull() {
        val holder = PendingNotificationTap()
        assertNull(holder.state.value)
    }

    @Test
    fun set_populatesState() {
        val holder = PendingNotificationTap()
        holder.set(convoA, msgA)

        val tap = holder.state.value
        assertNotNull(tap)
        assertEquals(convoA, tap.conversationId)
        assertEquals(msgA, tap.messageId)
    }

    @Test
    fun set_replacesPriorTap() {
        val holder = PendingNotificationTap()
        holder.set(convoA, msgA)
        holder.set(convoB, msgB)

        val tap = holder.state.value
        assertNotNull(tap)
        assertEquals(convoB, tap.conversationId)
        assertEquals(msgB, tap.messageId)
    }

    @Test
    fun clear_removesState() {
        val holder = PendingNotificationTap()
        holder.set(convoA, msgA)
        holder.clear()

        assertNull(holder.state.value)
    }

    @Test
    fun clearIfMatches_clearsOnMatch() {
        val holder = PendingNotificationTap()
        holder.set(convoA, msgA)
        holder.clearIfMatches(convoA)

        assertNull(holder.state.value)
    }

    @Test
    fun clearIfMatches_leavesStateOnMismatch() {
        val holder = PendingNotificationTap()
        holder.set(convoA, msgA)
        holder.clearIfMatches(convoB)

        // Mismatch must not clobber — another in-flight tap could have
        // already replaced the one we thought we were clearing.
        assertNotNull(holder.state.value)
        assertEquals(convoA, holder.state.value?.conversationId)
    }

    @Test
    fun clearIfMatches_onEmptyState_isNoOp() {
        val holder = PendingNotificationTap()
        holder.clearIfMatches(convoA)
        assertNull(holder.state.value)
    }
}
