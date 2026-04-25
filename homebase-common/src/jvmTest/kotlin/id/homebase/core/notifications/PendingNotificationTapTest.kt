package id.homebase.core.notifications

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Locks down the PendingNotificationTap contract consumed by
 * NotificationService and ConversationListViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingNotificationTapTest {

    private val convoA = Uuid.random()
    private val convoB = Uuid.random()
    private val msgA = Uuid.random()
    private val msgB = Uuid.random()

    // ---------- API contract (basic set/clear/clearIfMatches semantics) ----------

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

    // ---------- TTL auto-expiry ----------

    @Test
    fun tap_autoExpiresAfterTtl() = runTest {
        val holder = PendingNotificationTap(ttl = 10.seconds, scope = backgroundScope)
        holder.set(convoA, msgA)

        // Just before TTL elapses — still retrievable.
        advanceTimeBy(9_999)
        assertNotNull(holder.state.value, "tap must survive until ttl elapses")

        // Cross the boundary — expiry coroutine fires and clears.
        advanceTimeBy(2)
        advanceUntilIdle()
        assertNull(holder.state.value, "tap must be cleared after ttl")
    }

    @Test
    fun secondSet_cancelsFirstExpiry_andStartsFreshTtl() = runTest {
        val holder = PendingNotificationTap(ttl = 10.seconds, scope = backgroundScope)
        holder.set(convoA, msgA)
        advanceTimeBy(9_000)  // 9s into A's TTL

        holder.set(convoB, msgB)  // B's clock starts now
        advanceTimeBy(2_000)  // would have expired A (9s + 2s > 10s) but B is fresh
        advanceUntilIdle()

        val tap = holder.state.value
        assertNotNull(tap, "B's TTL hasn't elapsed yet — tap must still be present")
        assertEquals(convoB, tap.conversationId)

        // B expires on its own schedule.
        advanceTimeBy(9_000)  // total 11s on B's clock
        advanceUntilIdle()
        assertNull(holder.state.value, "B's tap must clear once its own ttl elapses")
    }

    @Test
    fun clear_cancelsExpiryJob_noLaterStateChange() = runTest {
        val holder = PendingNotificationTap(ttl = 10.seconds, scope = backgroundScope)
        holder.set(convoA, msgA)
        holder.clear()
        assertNull(holder.state.value)

        // Advance past when the expiry would have fired — no-op (job was cancelled).
        advanceTimeBy(15_000)
        advanceUntilIdle()
        assertNull(holder.state.value)
    }

    @Test
    fun clearIfMatches_cancelsExpiryJob_evenWhenStateAlreadyMatches() = runTest {
        val holder = PendingNotificationTap(ttl = 10.seconds, scope = backgroundScope)
        holder.set(convoA, msgA)
        holder.clearIfMatches(convoA)  // matches → clears + cancels expiry
        assertNull(holder.state.value)

        // The cancelled expiry must not later fire and clobber a NEW tap.
        holder.set(convoB, msgB)
        advanceTimeBy(5_000)  // well within B's TTL
        advanceUntilIdle()

        val tap = holder.state.value
        assertNotNull(tap, "B's tap must survive — A's cancelled expiry must not fire")
        assertEquals(convoB, tap.conversationId)
    }

    @Test
    fun expiredTap_isClearedFromStateFlow_observableViaState() = runTest {
        val holder = PendingNotificationTap(ttl = 1.seconds, scope = backgroundScope)
        holder.set(convoA, msgA)
        assertNotNull(holder.state.value)

        // Cross the boundary; state must transition to null reactively.
        advanceTimeBy(1_500)
        advanceUntilIdle()
        assertNull(holder.state.value)
    }
}
