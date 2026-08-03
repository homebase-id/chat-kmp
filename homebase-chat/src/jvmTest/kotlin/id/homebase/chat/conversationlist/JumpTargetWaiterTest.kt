@file:OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)

package id.homebase.chat.conversationlist

import id.homebase.resources.MR
import id.homebase.resources.conversation_jump_message_not_arrived
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Locks down the notification-tap jump wait (#1158).
 *
 * The bug: a push notification is by definition an announcement of something you
 * have not synced yet, and `loadConversationAroundMessage`'s opening
 * `getMessage(uid)` is a pure local SQL read. On the documented
 * `processAllInboxes()`-races-`syncAll()` window the row simply isn't there yet, and
 * the old code asserted a deletion ("That message is no longer available") it had
 * never verified.
 */
class JumpTargetWaiterTest {

    private val convo = Uuid.random()
    private val msg = Uuid.random()

    private class Rec {
        /** Snackbar sink — MUST stay empty on every non-timeout path. */
        val infos = mutableListOf<StringResource>()

        /** Every (messageId, waiting) push at the "pending jump" affordance, in order. */
        val waiting = mutableListOf<Pair<Uuid, Boolean>>()

        /** Messages the wait re-seeded a centered window around. */
        val seeded = mutableListOf<Uuid>()

        /** Messages a targeted sync was requested for. */
        val syncRequests = mutableListOf<Uuid>()
    }

    private fun waiter(
        rec: Rec,
        arrivals: Flow<Unit>,
        isMessageLocal: () -> Boolean,
        timeout: kotlin.time.Duration = 15.seconds,
    ) = JumpTargetWaiter(
        arrivals = arrivals,
        isMessageLocal = { isMessageLocal() },
        requestSync = { id -> rec.syncRequests += id },
        setWaiting = { id, on -> rec.waiting += id to on },
        seedWindowAround = { _, id -> rec.seeded += id },
        sendInfo = { rec.infos += it },
        timeout = timeout,
    )

    @Test
    fun messageAlreadyInSql_resolvesImmediately_noWaitingState_noToast() = runTest {
        val rec = Rec()
        val w = waiter(rec, arrivals = MutableSharedFlow(), isMessageLocal = { true })

        val outcome = w.awaitJumpTarget(convo, msg)

        assertEquals(JumpTargetOutcome.AlreadyLocal, outcome)
        // The fast path must not cost a frame: no virtual time elapsed, the pending-jump
        // affordance never flickered on, and nothing was re-seeded or synced.
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(rec.waiting.isEmpty(), "fast path must not touch the waiting affordance")
        assertTrue(rec.infos.isEmpty(), "fast path must not toast")
        assertTrue(rec.seeded.isEmpty())
        assertTrue(rec.syncRequests.isEmpty())
    }

    @Test
    fun messageAbsent_entersWaitingState_andDoesNotToast() = runTest {
        val rec = Rec()
        val w = waiter(rec, arrivals = MutableSharedFlow(), isMessageLocal = { false })

        val job = async { w.awaitJumpTarget(convo, msg) }
        runCurrent()

        assertEquals(listOf(msg to true), rec.waiting)
        assertTrue(rec.infos.isEmpty(), "a local miss must NOT claim the message was deleted")

        job.cancel()
    }

    @Test
    fun enteringTheWait_requestsATargetedSync() = runTest {
        val rec = Rec()
        val w = waiter(rec, arrivals = MutableSharedFlow(), isMessageLocal = { false })

        val job = async { w.awaitJumpTarget(convo, msg) }
        runCurrent()

        assertEquals(listOf(msg), rec.syncRequests)

        job.cancel()
    }

    @Test
    fun messageArrivesOnADataEvent_resolvesWait_seedsWindow_clearsWaiting_stillNoToast() = runTest {
        val rec = Rec()
        val arrivals = MutableSharedFlow<Unit>()
        var local = false
        val w = waiter(rec, arrivals = arrivals, isMessageLocal = { local })

        val job = async { w.awaitJumpTarget(convo, msg) }
        runCurrent()

        // The WS push (or the sync round) lands the row, then signals.
        local = true
        arrivals.emit(Unit)

        assertEquals(JumpTargetOutcome.Arrived, job.await())
        assertEquals(listOf(msg), rec.seeded, "the window must be re-seeded around the arrival")
        assertEquals(listOf(msg to true, msg to false), rec.waiting, "the affordance must be cleared on arrival")
        assertTrue(rec.infos.isEmpty(), "an arrival must never toast")
    }

    @Test
    fun messageLandsWithNoArrivalSignal_isStillPickedUpByTheBackstopPoll() = runTest {
        // `arrivals` is a plain Flow, so the instant the collector subscribes isn't
        // observable: a row landing between the caller's miss and that instant emits its
        // signal into the void. Without the poll nothing re-checks until the NEXT signal,
        // and if none comes the jump waits out the full budget and toasts for a message
        // that is sitting in SQL. `arrivals` here never emits at all — the harshest form
        // of that race.
        val rec = Rec()
        var local = false
        val w = waiter(rec, arrivals = MutableSharedFlow(), isMessageLocal = { local })

        val job = async { w.awaitJumpTarget(convo, msg) }
        runCurrent()

        local = true
        advanceTimeBy(JumpTargetWaiter.POLL_INTERVAL + 100.milliseconds)

        assertEquals(JumpTargetOutcome.Arrived, job.await())
        assertEquals(listOf(msg), rec.seeded, "the window must still be re-seeded")
        assertTrue(rec.infos.isEmpty(), "a message that did arrive must never toast")
    }

    @Test
    fun syncRoundCompletingWithoutTheMessage_doesNotEndTheWait() = runTest {
        // This is the race itself: on reconnect, syncAll() overtakes the processInbox
        // poke, QueryBatch returns 0 records and the round reports Completed while the
        // row is still in flight. A completed round is therefore NOT proof of absence,
        // and must not be allowed to terminate the wait.
        val rec = Rec()
        val arrivals = MutableSharedFlow<Unit>()
        var local = false
        val w = waiter(rec, arrivals = arrivals, isMessageLocal = { local })

        val job = async { w.awaitJumpTarget(convo, msg) }
        runCurrent()

        repeat(3) { arrivals.emit(Unit) }
        runCurrent()

        assertTrue(job.isActive, "a 0-record sync round must not end the wait")
        assertTrue(rec.infos.isEmpty())

        // ... and the late WS push still resolves it.
        local = true
        arrivals.emit(Unit)
        assertEquals(JumpTargetOutcome.Arrived, job.await())
        assertTrue(rec.infos.isEmpty())
    }

    @Test
    fun noArrivalWithinTimeout_toastsTheHonestCopy_andClearsWaiting() = runTest {
        val rec = Rec()
        val w = waiter(
            rec,
            arrivals = MutableSharedFlow(),
            isMessageLocal = { false },
            timeout = 15.seconds,
        )

        val job = async { w.awaitJumpTarget(convo, msg) }
        runCurrent()

        advanceTimeBy(14.seconds)
        runCurrent()
        assertTrue(rec.infos.isEmpty(), "must not give up before the timeout")

        advanceTimeBy(2.seconds)

        assertEquals(JumpTargetOutcome.TimedOut, job.await())
        // The honest copy — never the one that asserts a deletion we never verified.
        assertEquals(listOf(MR.string.conversation_jump_message_not_arrived), rec.infos)
        assertEquals(listOf(msg to true, msg to false), rec.waiting)
        assertTrue(rec.seeded.isEmpty())
    }

    @Test
    fun cancellationClearsTheWaitingAffordance() = runTest {
        // Switching conversations cancels currentConversationJob; the pending-jump
        // spinner must not be stranded on the next conversation.
        val rec = Rec()
        val w = waiter(rec, arrivals = MutableSharedFlow(), isMessageLocal = { false })

        val job = async { w.awaitJumpTarget(convo, msg) }
        runCurrent()
        job.cancel()
        runCurrent()

        assertEquals(listOf(msg to true, msg to false), rec.waiting)
        assertTrue(rec.infos.isEmpty())
    }

    @Test
    fun onlyNotificationTapsWait_everyOtherJumpStillReportsUnavailable() = runTest {
        // A search hit or album item was just rendered from local data, so a miss
        // there really is a deletion — those keep the existing copy and the
        // existing immediate report.
        assertTrue(shouldWaitForJumpTarget(ConversationLoadTrigger.NotificationResolved))
        assertTrue(!shouldWaitForJumpTarget(ConversationLoadTrigger.Navigation))
        assertTrue(!shouldWaitForJumpTarget(ConversationLoadTrigger.ShareIntent))
        assertTrue(!shouldWaitForJumpTarget(ConversationLoadTrigger.Unknown))
    }

    @Test
    fun timeoutDefaultsToTheDocumentedBudget() = runTest {
        // 10-15s per the issue; the log evidence shows late WS pushes at +0.7s/+3s/+30s/+40s
        // after the round reported Completed, so the upper end of the range is the useful one.
        assertEquals(15.seconds, JumpTargetWaiter.DEFAULT_TIMEOUT)
    }
}
