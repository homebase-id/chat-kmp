package id.homebase.chat.services.convo

import id.homebase.api.common.time.UnixTimeUtc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Verifies the lastRead writeback path on [ConversationService]:
 *   - ChatReadCount upsert is eager (so UnreadCountEnricher.applyLocalAdvance,
 *     which queries it via SQL, sees fresh values on the same frame).
 *   - The conversation-file optimistic stamp and the outbox enqueue are
 *     deferred and coalesce to a single per-conversation flush.
 *
 * **Why `runBlocking` and not `runTest`.** The service's debounce is a
 * `scope.launch { delay(N); flushDirtyLastRead() }`. Under `runTest`/TestScope
 * the StandardTestDispatcher interleaves launched coroutines with the test
 * body in ways that let a fraction of those launched coroutines slip past
 * `cancel()` and execute their flush before the test's explicit drain runs,
 * contaminating the per-flush counters. `runBlocking` gives a real scope
 * with a real wall-clock `delay`, and the fixture passes
 * `Long.MAX_VALUE / 2` as the debounce so the timer cannot fire during
 * the test — every flush comes through [ConversationService.flushLastReadNow].
 *
 * Counts stamps via the `fileMetadata.updated` delta: every stamp bumps
 * `updated` by exactly 1 ms (see `OptimisticWriter.stampConversationLocalAppData` —
 * `existingFile.fileMetadata.updated.addMilliseconds(1)`).
 */
class ConversationServiceLastReadDebounceTest {

    /**
     * Real scope wrapping the JVM's IO pool — explicitly NOT a TestScope. The
     * test body uses `runBlocking` so suspend calls execute on real threads,
     * and the service's `delay(Long.MAX_VALUE / 2)` parks the timer for the
     * lifetime of the universe so nothing fires until the test explicitly
     * calls `flushLastReadNow`. Cancelled at the end of each test.
     */
    private fun newServiceScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Test
    fun burstOnOneConversation_coalescesToSingleStampAndEnqueue() = runBlocking {
        val serviceScope = newServiceScope()
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = serviceScope)
                val convoId = fixture.seedOneOnOne(other = "alice.test")

                val initialUpdatedMs = fixture.getConversationFile(convoId)!!
                    .fileMetadata.updated.milliseconds
                val initialOutboxRows = fixture.outboxRowCount()

                // 50 rapid monotonically-increasing advances. Each call should
                // upsert ChatReadCount immediately and only schedule the deferred
                // stamp + enqueue.
                repeat(50) { i ->
                    service.updateLocalLastReadTime(
                        conversationId = convoId,
                        newLastReadTime = UnixTimeUtc(1_000L + i.toLong()),
                    )
                }

                // ChatReadCount must reflect the latest target immediately —
                // eager upsert is the whole point of keeping it out of the
                // debounce (so applyLocalAdvance's SQL read works).
                assertEquals(
                    1_049L,
                    fixture.dbm.chatReadCount.selectLastReadTimeMs(convoId),
                    "ChatReadCount must reflect the latest advance immediately",
                )

                // Drain the pending writeback synchronously.
                service.flushLastReadNow()

                val finalUpdatedMs = fixture.getConversationFile(convoId)!!
                    .fileMetadata.updated.milliseconds
                assertEquals(
                    1L,
                    finalUpdatedMs - initialUpdatedMs,
                    "Conversation-file must be stamped EXACTLY once across the burst — " +
                        "expected a single 1ms `updated` bump (got delta=${finalUpdatedMs - initialUpdatedMs})",
                )
                assertEquals(
                    initialOutboxRows + 1,
                    fixture.outboxRowCount(),
                    "Burst of 50 advances must coalesce into exactly one outbox row",
                )
            }
        } finally { serviceScope.cancel() }
    }

    @Test
    fun burstAcrossMultipleConversations_eachGetsOneStampAndEnqueue() = runBlocking {
        val serviceScope = newServiceScope()
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = serviceScope)
                val convoA = fixture.seedOneOnOne(other = "alice.test")
                val convoB = fixture.seedOneOnOne(other = "bob.test")
                val convoC = fixture.seedOneOnOne(other = "carol.test")

                val initials = listOf(convoA, convoB, convoC).associateWith {
                    fixture.getConversationFile(it)!!.fileMetadata.updated.milliseconds
                }
                val initialOutboxRows = fixture.outboxRowCount()

                // Several advances per conversation, interleaved.
                repeat(10) { i ->
                    listOf(convoA, convoB, convoC).forEach { id ->
                        service.updateLocalLastReadTime(id, UnixTimeUtc(1_000L + i.toLong()))
                    }
                }

                service.flushLastReadNow()

                listOf(convoA, convoB, convoC).forEach { id ->
                    val delta = fixture.getConversationFile(id)!!.fileMetadata.updated.milliseconds -
                        initials.getValue(id)
                    assertEquals(
                        1L, delta,
                        "Each conversation must be stamped exactly once per flush; convo=$id delta=$delta",
                    )
                }
                assertEquals(
                    initialOutboxRows + 3,
                    fixture.outboxRowCount(),
                    "Three conversations × one flush should produce three outbox rows",
                )
            }
        } finally { serviceScope.cancel() }
    }

    @Test
    fun nonAdvancingCall_isNoOp() = runBlocking {
        val serviceScope = newServiceScope()
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = serviceScope)
                val convoId = fixture.seedOneOnOne(other = "alice.test")

                service.updateLocalLastReadTime(convoId, UnixTimeUtc(5_000L))
                service.flushLastReadNow()

                val afterFirstFlushUpdatedMs = fixture.getConversationFile(convoId)!!
                    .fileMetadata.updated.milliseconds
                val afterFirstFlushOutbox = fixture.outboxRowCount()
                assertEquals(
                    5_000L,
                    fixture.dbm.chatReadCount.selectLastReadTimeMs(convoId),
                    "ChatReadCount must reflect the first advance",
                )

                // Try to advance backwards — should be gated out before any
                // state mutation. We still call flushLastReadNow after to prove
                // the gate prevented even a deferred stamp.
                service.updateLocalLastReadTime(convoId, UnixTimeUtc(4_999L))
                service.flushLastReadNow()

                assertEquals(
                    5_000L,
                    fixture.dbm.chatReadCount.selectLastReadTimeMs(convoId),
                    "Gate must reject candidate <= current; ChatReadCount must stay at 5000",
                )
                assertEquals(
                    afterFirstFlushUpdatedMs,
                    fixture.getConversationFile(convoId)!!.fileMetadata.updated.milliseconds,
                    "Rejected advance must not bump conv-file `updated`",
                )
                assertEquals(
                    afterFirstFlushOutbox,
                    fixture.outboxRowCount(),
                    "Rejected advance must not enqueue an outbox row",
                )
            }
        } finally { serviceScope.cancel() }
    }

    @Test
    fun flushLastReadNow_isIdempotentWhenNothingPending() = runBlocking {
        val serviceScope = newServiceScope()
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = serviceScope)
                val convoId = fixture.seedOneOnOne(other = "alice.test")

                val initialUpdatedMs = fixture.getConversationFile(convoId)!!
                    .fileMetadata.updated.milliseconds
                val initialOutboxRows = fixture.outboxRowCount()

                service.updateLocalLastReadTime(convoId, UnixTimeUtc(7_000L))
                service.flushLastReadNow()

                assertEquals(
                    1L,
                    fixture.getConversationFile(convoId)!!.fileMetadata.updated.milliseconds -
                        initialUpdatedMs,
                    "flushLastReadNow must stamp once",
                )
                assertEquals(
                    initialOutboxRows + 1,
                    fixture.outboxRowCount(),
                    "flushLastReadNow must enqueue once",
                )

                // Second + third call with nothing pending must be a clean no-op.
                service.flushLastReadNow()
                service.flushLastReadNow()
                assertEquals(
                    initialOutboxRows + 1,
                    fixture.outboxRowCount(),
                    "flushLastReadNow with empty pending must not enqueue anything",
                )
                assertEquals(
                    1L,
                    fixture.getConversationFile(convoId)!!.fileMetadata.updated.milliseconds -
                        initialUpdatedMs,
                    "flushLastReadNow with empty pending must not stamp anything",
                )
            }
        } finally { serviceScope.cancel() }
    }

    @Test
    fun multipleAdvancesBeforeFlush_writesLatestTargetIntoLocalAppData() = runBlocking {
        val serviceScope = newServiceScope()
        try {
            ConversationServiceTestFixture().use { fixture ->
                val service = fixture.build(scope = serviceScope)
                val convoId = fixture.seedOneOnOne(other = "alice.test")

                val initialUpdatedMs = fixture.getConversationFile(convoId)!!
                    .fileMetadata.updated.milliseconds
                val initialOutboxRows = fixture.outboxRowCount()

                // Two distinct advances within the (notional) debounce window:
                // each reschedules the timer, the second target is what should
                // ultimately reach the conv-file.
                service.updateLocalLastReadTime(convoId, UnixTimeUtc(2_000L))
                service.updateLocalLastReadTime(convoId, UnixTimeUtc(3_000L))

                service.flushLastReadNow()

                val updated = fixture.getConversationFile(convoId)
                assertNotNull(updated)
                assertEquals(
                    1L,
                    updated.fileMetadata.updated.milliseconds - initialUpdatedMs,
                    "Two advances followed by one flush must produce exactly one stamp",
                )
                assertEquals(
                    initialOutboxRows + 1,
                    fixture.outboxRowCount(),
                    "Two advances followed by one flush must produce exactly one outbox row",
                )
                assertEquals(
                    3_000L,
                    fixture.dbm.chatReadCount.selectLastReadTimeMs(convoId),
                    "ChatReadCount must reflect the higher of the two advances",
                )

                // The conv-file's localAppData carries the higher target — the
                // superseded 2000 never makes it to disk in this code path.
                val localAppData = updated.fileMetadata.localAppData
                assertNotNull(localAppData, "localAppData must be present after stamp")
                assertTrue(
                    localAppData.content?.contains("\"lastReadTime\":3000") == true,
                    "Stamped conv-file must carry the latest target (3000), not the superseded 2000 — " +
                        "got localAppData.content=${localAppData.content}",
                )
            }
        } finally { serviceScope.cancel() }
    }
}
