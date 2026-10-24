@file:OptIn(ExperimentalUuidApi::class, ExperimentalCoroutinesApi::class)

package id.homebase.chat.services

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import id.homebase.api.client.eventbus.BackendEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Regression coverage for #1135 — a DriveSync/WS write that commits *between*
 * the start of `loadConversation`'s fetch and `setInitialWindow`.
 *
 * The fetch reads a pre-write SQLite snapshot, and both reconcilers
 * (`refreshCachedWindows` on `DriveEvent.Stopped`, `processIncrementalBatch` on
 * `DataEvent.BatchReceived`) skip a conversation that has no window yet — so the
 * message was dropped by all three paths and the stale window was cached and
 * reused on every subsequent open.
 *
 * The race is made deterministic with [GatedSqlDriver]: it lets the paging
 * SELECT run, then parks the reader thread *after* the snapshot has been taken.
 * The test writes the racing row and fires the sync event while the reader is
 * parked, then releases it.
 */
class ChatMessageStreamLoadRaceTest {

    private val chatDriveId = ChatMessageStreamTestFixture.chatDriveId
    private val conversationId = ChatMessageStreamTestFixture.conversationId
    private lateinit var gate: GatedSqlDriver

    private suspend fun buildFixture(scope: TestScope) =
        ChatMessageStreamTestFixture.build(scope) { GatedSqlDriver(it).also { gate = it } }

    // Connection warm-up (sqlite_master, PRAGMAs), #887's pinned-bar refresh (a
    // DriveLocalTagIndex read), and background service reads all pass through the
    // gate on the same 4-wide read pool as the message-page fetch. An unqualified
    // armNextRead() matches whichever of them the pool happens to run first, so the
    // gate can park on the wrong query and mis-sequence the choreography — it passes
    // locally but hangs on CI (runTest timeout). This index hint is emitted only by
    // the per-conversation message-paging SELECT (groupId filter + userDate sort),
    // so it parks on that read alone.
    private val messagePageRead: (String) -> Boolean = { it.contains("idx_chatmessage_convid_userDate") }

    @Test
    fun loadConversation_rowCommittedMidFetch_isRecoveredViaDriveSyncStopped() = runTest {
        val fixture = buildFixture(this)
        val alreadySynced = fixture.seedMessage(userDateMs = 1_000L)
        val racingMessage = fixture.buildMessageFile(userDateMs = 2_000L)

        val fetchA = gate.armNextRead(messagePageRead)
        val load = launch { fixture.stream.loadConversation(conversationId) }
        advanceUntilIdle()

        // Reader is now parked holding the pre-write snapshot.
        fetchA.reached.await()
        fixture.commit(racingMessage)
        val refreshDone = fixture.awaitPostSyncRefresh()
        fixture.eventBus.emit(
            BackendEvent.DriveEvent.Stopped(
                driveId = chatDriveId,
                totalCount = 1,
                result = BackendEvent.DriveResult.Completed,
            )
        )
        refreshDone.await()

        fetchA.release()
        load.join()

        val racingId = racingMessage.fileMetadata.appData.uniqueId!!
        assertTrue(
            fixture.stream.isMessageInWindow(conversationId, alreadySynced),
            "the pre-existing message must still be in the window",
        )
        assertTrue(
            fixture.stream.isMessageInWindow(conversationId, racingId),
            "a row committed while loadConversation was mid-fetch must end up in the window — " +
                "the fetch's snapshot predates it and both reconcilers skip a windowless conversation",
        )
        fixture.close()
    }

    @Test
    fun loadConversation_rowCommittedMidFetch_isRecoveredViaBatchReceived() = runTest {
        val fixture = buildFixture(this)
        fixture.seedMessage(userDateMs = 1_000L)
        val racingMessage = fixture.buildMessageFile(userDateMs = 2_000L)

        // processIncrementalBatch maps on Dispatchers.Default before reaching its
        // window gate, so "the event was emitted" is not "the gate was evaluated".
        // isConversationLeft is called immediately before that gate with no
        // suspension in between — use it as the barrier.
        val batchAtWindowGate = CompletableDeferred<Unit>()
        fixture.stream.isConversationLeft = { id ->
            if (id == conversationId) batchAtWindowGate.complete(Unit)
            false
        }

        val fetchA = gate.armNextRead(messagePageRead)
        val load = launch { fixture.stream.loadConversation(conversationId) }
        advanceUntilIdle()

        fetchA.reached.await()
        fixture.commit(racingMessage)
        fixture.eventBus.emit(
            BackendEvent.DataEvent.BatchReceived(
                driveId = chatDriveId,
                batchData = listOf(racingMessage),
            )
        )
        batchAtWindowGate.await()

        fetchA.release()
        load.join()

        assertTrue(
            fixture.stream.isMessageInWindow(
                conversationId,
                racingMessage.fileMetadata.appData.uniqueId!!,
            ),
            "a WS-pushed batch for a conversation whose initial load is mid-fetch must not be dropped",
        )
        fixture.close()
    }

    @Test
    fun loadConversation_rowCommittedDuringTheReRead_survivesTheWriteBack() = runTest {
        // The re-read has its own snapshot boundary. By then the window exists, so a
        // batch landing mid-re-read IS upserted into it by processIncrementalBatch —
        // but the re-read's own write-back would replace that window with a page that
        // predates the upsert. Same permanent loss as #1135, one fetch later, and with
        // no reconciler left to heal it until the next DriveEvent.Stopped.
        val fixture = buildFixture(this)
        fixture.seedMessage(userDateMs = 1_000L)
        val duringFetchA = fixture.buildMessageFile(userDateMs = 2_000L)
        val duringReRead = fixture.buildMessageFile(userDateMs = 3_000L)

        val batchAtWindowGate = CompletableDeferred<Unit>()
        fixture.stream.isConversationLeft = { id ->
            if (id == conversationId) batchAtWindowGate.complete(Unit)
            false
        }

        val fetchA = gate.armNextRead(messagePageRead)
        val load = launch { fixture.stream.loadConversation(conversationId) }
        advanceUntilIdle()

        // Commit during the initial fetch — this is what triggers the re-read.
        fetchA.reached.await()
        fixture.commit(duringFetchA)
        val refreshDone = fixture.awaitPostSyncRefresh()
        fixture.eventBus.emit(
            BackendEvent.DriveEvent.Stopped(
                driveId = chatDriveId,
                totalCount = 1,
                result = BackendEvent.DriveResult.Completed,
            )
        )
        refreshDone.await()

        // Arm the re-read's query before letting the initial fetch finish.
        val reRead = gate.armNextRead(messagePageRead)
        fetchA.release()
        reRead.reached.await()

        // Commit during the re-read. The window exists now, so the WS path upserts it.
        fixture.commit(duringReRead)
        fixture.eventBus.emit(
            BackendEvent.DataEvent.BatchReceived(
                driveId = chatDriveId,
                batchData = listOf(duringReRead),
            )
        )
        batchAtWindowGate.await()

        reRead.release()
        load.join()

        assertTrue(
            fixture.stream.isMessageInWindow(
                conversationId,
                duringFetchA.fileMetadata.appData.uniqueId!!,
            ),
            "the re-read must still deliver the row that triggered it",
        )
        assertTrue(
            fixture.stream.isMessageInWindow(
                conversationId,
                duringReRead.fileMetadata.appData.uniqueId!!,
            ),
            "a row upserted into the window while the re-read was in flight must survive " +
                "its write-back — the re-read merges, it does not replace",
        )
        fixture.close()
    }

    @Test
    fun loadConversationAroundMessage_rowCommittedMidFetch_isRecovered() = runTest {
        // The scroll-anchored open — the common cold-open path — builds its window
        // with the same read-then-register shape and must be equally immune.
        val fixture = buildFixture(this)
        val anchor = fixture.seedMessage(userDateMs = 1_000L)
        val racingMessage = fixture.buildMessageFile(userDateMs = 2_000L)

        // loadAround issues anchor-lookup → older half (DESC) → newer half (ASC).
        // Park on the newer half: it is the last read, and the racing row sorts
        // into it, so the pre-park snapshot provably excludes it.
        val fetchA = gate.armNextRead { messagePageRead(it) && it.contains("ASC") }
        val load = launch { fixture.stream.loadConversationAroundMessage(conversationId, anchor) }
        advanceUntilIdle()

        fetchA.reached.await()
        fixture.commit(racingMessage)
        val refreshDone = fixture.awaitPostSyncRefresh()
        fixture.eventBus.emit(
            BackendEvent.DriveEvent.Stopped(
                driveId = chatDriveId,
                totalCount = 1,
                result = BackendEvent.DriveResult.Completed,
            )
        )
        refreshDone.await()

        fetchA.release()
        load.join()

        assertTrue(
            fixture.stream.isMessageInWindow(
                conversationId,
                racingMessage.fileMetadata.appData.uniqueId!!,
            ),
            "a row committed while loadAround was mid-fetch must end up in the window",
        )
        fixture.close()
    }

    @Test
    fun loadConversation_withoutAConcurrentWrite_issuesOnePagingQuery() = runTest {
        // Guard on the other side of the fix: the re-read must be conditional,
        // not an unconditional second page fetch on every conversation open.
        // The pinned-bar read that also runs on open is a separate query (it hits
        // DriveLocalTagIndex, the message paging query does not), so it is excluded
        // from the count below.
        val fixture = buildFixture(this)
        val onlyMessage = fixture.seedMessage(userDateMs = 1_000L)
        val sqlBefore = gate.readSqls.size

        fixture.stream.loadConversation(conversationId)
        advanceUntilIdle()

        assertTrue(fixture.stream.isMessageInWindow(conversationId, onlyMessage))
        val pagingQueries = gate.readSqls.drop(sqlBefore)
            .count { !it.contains("DriveLocalTagIndex") }
        assertEquals(
            1, pagingQueries,
            "an uncontended load must issue exactly one paging query",
        )
        fixture.close()
    }
}

/**
 * `SqlDriver` decorator that parks the reader thread immediately AFTER an armed
 * `executeQuery` has run — the query's result set is already materialised, so the
 * caller provably holds a pre-park snapshot of the database while the test mutates
 * it from another thread.
 *
 * One stop is armed at a time; a test that needs to park twice (initial fetch and
 * re-read) arms the second before releasing the first.
 *
 * Blocking here is intentional and safe: reads run on `DatabaseManager`'s IO
 * read dispatcher, never on the test scheduler.
 */
private class GatedSqlDriver(private val delegate: SqlDriver) : SqlDriver {

    class ReadStop internal constructor(internal val matches: (String) -> Boolean) {
        internal val gate = CountDownLatch(1)

        /** Completes once the parked query has produced its snapshot. */
        val reached = CompletableDeferred<Unit>()

        fun release() = gate.countDown()
    }

    private val armed = AtomicReference<ReadStop?>(null)
    private val reads = AtomicInteger(0)
    private val readSql = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Total `executeQuery` calls seen; tests read deltas around a single call. */
    val readCount: Int get() = reads.get()

    /** SQL of every `executeQuery`, in order — lets tests count reads by query shape. */
    val readSqls: List<String> get() = readSql.toList()

    /**
     * Park on the next query whose SQL satisfies [matching]. There is deliberately
     * no match-all default: connection warm-up (sqlite_master, PRAGMAs), the pinned
     * bar's DriveLocalTagIndex read, and background service reads all pass through
     * this gate on the same read pool, so a caller must name the query it means to
     * park on or it will race them.
     */
    fun armNextRead(matching: (String) -> Boolean): ReadStop =
        ReadStop(matching).also { armed.set(it) }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        val candidate = armed.get()
        val stop = candidate
            ?.takeIf { it.matches(sql) && armed.compareAndSet(it, null) }
        reads.incrementAndGet()
        readSql.add(sql)
        val result = delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        if (stop != null) {
            stop.reached.complete(Unit)
            stop.gate.await()
        }
        return result
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> = delegate.execute(identifier, sql, parameters, binders)

    override fun newTransaction(): QueryResult<Transacter.Transaction> = delegate.newTransaction()

    override fun currentTransaction(): Transacter.Transaction? = delegate.currentTransaction()

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) =
        delegate.addListener(queryKeys = queryKeys, listener = listener)

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) =
        delegate.removeListener(queryKeys = queryKeys, listener = listener)

    override fun notifyListeners(vararg queryKeys: String) =
        delegate.notifyListeners(queryKeys = queryKeys)

    override fun close() = delegate.close()
}
