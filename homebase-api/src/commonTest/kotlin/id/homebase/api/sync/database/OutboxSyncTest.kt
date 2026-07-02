package id.homebase.api.sync.database

import id.homebase.api.client.drives.files.DeleteFilesByGroupIdOutboxRequest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import co.touchlab.kermit.Logger
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import id.homebase.api.client.ClientException
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.ProblemDetails
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.upload.StagedPayloadMissingException

class TestUploader : OutboxUploader {
    var shouldFail = false
    var failureException: Throwable? = null

    // `uploaded` is appended to from up to MAX_SENDING_THREADS=3 concurrent send
    // coroutines inside OutboxSync. On Kotlin/Native a plain `mutableListOf` racing
    // `add()` from multiple coroutines can surface as ConcurrentModificationException
    // with `at null:-1` (JVM's ArrayList silently corrupts but doesn't fail-fast on
    // add). Keep the list as immutable snapshots inside an atomicfu reference; readers
    // get a stable List<Outbox> view per access.
    private val _uploaded = atomic<List<Outbox>>(emptyList())
    val uploaded: List<Outbox> get() = _uploaded.value

    // For concurrency testing
    private val currentActive = atomic(0)
    var maxActive = 0

    override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) {
        Logger.i("Uploading item")

        val current = currentActive.incrementAndGet()
        maxActive = maxOf(maxActive, current)

        eventBus.emit(
            BackendEvent.OutboxEvent.ItemProgress(
                outboxRecord.driveId, outboxRecord.uniqueId, 0.5F
            )
        )

        failureException?.let {
            currentActive.decrementAndGet()
            throw it
        }

        if (shouldFail) {
            currentActive.decrementAndGet()
            throw Exception("Test failure")
        }

        // Virtual delay to simulate upload time (critical for concurrency observation)
        kotlinx.coroutines.delay(1000)

        _uploaded.update { it + outboxRecord }
        currentActive.decrementAndGet()
    }
}

private fun clientException(
    status: Int = 400,
    errorCode: OdinClientErrorCode = OdinClientErrorCode.UnhandledScenario,
    message: String,
): ClientException = ClientException(
    status = status,
    errorCode = errorCode,
    message = message,
    correlationId = null,
    problem = ProblemDetails(status = status, title = message),
)


/**
 * iOS-sim teardown race (root cause). OutboxSync's worker coroutines run on
 * `backgroundScope` (virtual time), but every DB call hops to a *real* dispatcher
 * via `OutboxWrapper` → `DatabaseManager.withWriteValue`/`readValue`
 * (`Dispatchers.Default.limitedParallelism(1)` for writes, `Dispatchers.IO` for
 * reads). `advanceUntilIdle()` only drains the *virtual* scheduler, so a coroutine
 * parked inside `withContext(realDispatcher)` looks idle and the test body proceeds
 * while real-thread DB work is still in flight. When the `runTest` block then
 * returned and `db.close()` ran, that late work raced `NativeSqliteDriver`'s
 * connection-pool teardown and surfaced as `ConcurrentModificationException at
 * null:-1` (occasionally a segfault). JVM never reproduced it — `JdbcSqliteDriver`
 * is single-connection and serializes implicitly.
 *
 * Fix (option 1): [runOutboxTest] binds the DatabaseManager's read+write
 * dispatchers to runTest's virtual-time `testScheduler`. Now `advanceUntilIdle()`
 * genuinely drains all DB work, so the outbox is quiescent before `db.close()` —
 * no real-thread work outlives the test on any target. The helper also owns the
 * `db.close()` in a `finally`.
 *
 * Two tests can't use the helper because their semantics depend on the real/virtual
 * time split (each constructs its own real-dispatcher DatabaseManager and says why):
 * `testFailureAndRetry` and `testTryEnqueueDoesNotBlockOnSaturatedEventBus`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutboxSyncTest {

    /**
     * Runs an outbox test with the DatabaseManager's read+write dispatchers bound to
     * runTest's virtual-time [TestScope.testScheduler] instead of the production
     * real-thread dispatchers. See the class KDoc for why this closes the iOS-sim
     * `ConcurrentModificationException at null:-1` teardown race. The DatabaseManager
     * is created inside `runTest` (so it can see `testScheduler`) and closed in a
     * `finally`.
     */
    private fun runOutboxTest(body: suspend TestScope.(db: DatabaseManager) -> Unit) = runTest {
        val dbDispatcher = StandardTestDispatcher(testScheduler)
        val db = DatabaseManager(
            { createInMemoryDatabase() },
            dispatcher = dbDispatcher,
            readDispatcher = dbDispatcher,
        )
        try {
            body(db)
        } finally {
            db.close()
        }
    }


    @Test
    fun reapIdleOutboxTemps_deletesOldOrphansOnlyWhenIdle() = runOutboxTest { db ->
        val sync = OutboxSync(
            databaseManager = db, uploader = TestUploader(), eventBus = EventBus(), scope = backgroundScope
        )
        val fs = FakeFileSystem()
        val dir = "/cache/outbox-temp".toPath()
        fs.createDirectories(dir)
        fun write(name: String): Long {
            val p = dir / name
            fs.write(p) { writeUtf8("x") }
            return fs.metadata(p).lastModifiedAtMillis!!
        }

        // Idle + OLDER than maxAge → reaped (a real crash-orphan).
        val m1 = write("enc_old.encrypted")
        sync.reapIdleOutboxTemps(dir.toString(), maxAgeMs = 1_000, fileSystem = fs, nowMs = m1 + 5_000)
        assertFalse(fs.exists(dir / "enc_old.encrypted"), "idle + old orphan must be reaped")

        // Idle + YOUNGER than maxAge → kept (the create-race guard: a just-written temp is safe).
        val m2 = write("enc_new.encrypted")
        sync.reapIdleOutboxTemps(dir.toString(), maxAgeMs = 1_000, fileSystem = fs, nowMs = m2 + 500)
        assertTrue(fs.exists(dir / "enc_new.encrypted"), "a just-written temp must never be reaped")

        // NON-EMPTY outbox + old file → kept (a pending/offline row might reference it).
        sync.tryEnqueue(DeleteFilesByGroupIdOutboxRequest(driveId = Uuid.random(), groupIds = listOf(Uuid.random())))
        val m3 = write("enc_pending.encrypted")
        sync.reapIdleOutboxTemps(dir.toString(), maxAgeMs = 1_000, fileSystem = fs, nowMs = m3 + 5_000)
        assertTrue(fs.exists(dir / "enc_pending.encrypted"), "must not reap while the outbox is non-empty")
    }

    @Test
    fun testSuccessfulSend() = runOutboxTest { db ->
        val eventBus = EventBus()  // Fresh instance per test

        // We cannot use "use" in these tests since it'll mess up waiting for threads
        val uploader = TestUploader()

        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )
        sync.setOnline(true)

        // This will count total number of items sent via the events.
        // It's necessary to ensure all threads are finished.
        // This must be setup in the beginning of the test before we send()
        val completedDeferred = async {
            eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.Completed>()
                .first().totalCount
        }
        // Kick off the async collector before we send
        testScheduler.runCurrent()

        // Insert a record
        val driveId = Uuid.random()
        val uniqueId = Uuid.random()
        db.outbox.insert(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 0,
            uploadType = 0,
            json = byteArrayOf(),
            filePaths = null
        )

        // Trigger send
        val started = sync.send()
        assertTrue(started, "Should start sending")

        // Advance time to let coroutines complete
        advanceUntilIdle()

        // Wait for the final events too
        val completedCount = completedDeferred.await()

        // Assertions
        assertEquals(1, completedCount)
        assertEquals(1, uploader.uploaded.size)
        assertEquals(driveId, uploader.uploaded[0].driveId)
        assertEquals(uniqueId, uploader.uploaded[0].uniqueId)
        // Check that item was deleted
        assertEquals(0L, db.outbox.count())
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }

    @Test
    fun testFailureAndRetry() {
        // NOT runOutboxTest (see class KDoc): this test must freeze after exactly one
        // failed attempt. With the DB on virtual time, advanceUntilIdle() would drain
        // the entire 20-step backoff schedule and the row would be dropped (count==0)
        // instead of re-queued (count==1). Keep the production real-thread dispatchers
        // so the retry's scheduled delay parks instead of being advanced through.
        val db = DatabaseManager({ createInMemoryDatabase() })

        runTest {
            val eventBus = EventBus()  // Fresh instance per test

            val uploader = TestUploader()
            uploader.shouldFail = true

            val sync = OutboxSync(
                databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
            )
            sync.setOnline(true)

            val completedDeferred = async {
                eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.Completed>()
                    .first().totalCount
            }
            testScheduler.runCurrent() // Kick off the async collector

            // Insert a record
            val driveId = Uuid.random()
            val fileId = Uuid.random()
            db.outbox.insert(
                driveId = driveId,
                uniqueId = fileId,
                dependencyUniqueId = null,
                priority = 0,
                uploadType = 0,
                json = byteArrayOf(),
                filePaths = null
            )

            try {
                sync.send()
            } catch (e: Exception) {
                // It's meant to fail, snatch the exception without an error in the log
            }
            advanceUntilIdle()

            // Wait for the final events too
            val completedCount = completedDeferred.await()

            assertEquals(0, completedCount)
            // Item should not be deleted, count should still be 1
            assertEquals(1L, db.outbox.count())
            // Check that uploader was called but failed (not added to uploaded)
            assertEquals(0, uploader.uploaded.size)
            sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
        }
        db.closeIgnoringTeardownRace()  // real-dispatcher teardown race; see helper
    }

    @Test
    fun testConcurrencyLimit() = runOutboxTest { db ->
        val eventBus = EventBus()  // Fresh instance per test

        val uploader = TestUploader()

        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )
        sync.setOnline(true)

        val completedDeferred = async {
            eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.Completed>()
                .first().totalCount
        }
        testScheduler.runCurrent() // Kick off the async collector

        // Insert 5 records
        val records = (1..5).map {
            val driveId = Uuid.random()
            val fileId = Uuid.random()
            db.outbox.insert(
                driveId = driveId,
                uniqueId = fileId,
                dependencyUniqueId = null,
                priority = 0,
                uploadType = 0,
                json = byteArrayOf(),
                filePaths = null
            )
            Pair(driveId, fileId)
        }

        // Start sending - should spawn up to 3 threads
        val started1 = sync.send()
        assertTrue(started1)

        advanceUntilIdle()

        // Wait for the final events too
        val completedCount = completedDeferred.await()

        // Assertions
        assertEquals(5, completedCount)
        assertTrue(uploader.maxActive <= 3)

        // Should have processed 3 items initially (since semaphore allows 3)
        assertEquals(records.size, uploader.uploaded.size)
        // 0 items should remain
        assertEquals(0L, db.outbox.count())
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }

    @Test
    fun testMaxRetriesDrop() = runOutboxTest { db ->
        val eventBus = EventBus()
        val uploader = TestUploader()
        uploader.shouldFail = true

        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )
        sync.setOnline(true)

        val droppedDeferred = async {
            eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.OutboxItemDropped>().first()
        }
        testScheduler.runCurrent()

        // Insert a record and pre-set checkOutCount to 19 (one attempt away from the limit of 20)
        val driveId = Uuid.random()
        val uniqueId = Uuid.random()
        db.outbox.insert(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 0,
            uploadType = 0,
            json = byteArrayOf(),
            filePaths = null
        )
        db.driver.execute(null, "UPDATE Outbox SET checkOutCount = 19", 0)

        assertEquals(1L, db.outbox.count())

        try {
            sync.send()
        } catch (_: Exception) {
        }
        advanceUntilIdle()

        val dropped = droppedDeferred.await()

        // Item should be dropped — removed from outbox
        assertEquals(0L, db.outbox.count())
        assertEquals(uniqueId, dropped.uniqueId)
        assertEquals(driveId, dropped.driveId)
        assertEquals(20, dropped.attempts)
        assertEquals("retries exhausted (20)", dropped.reason)
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }

    /**
     * Regression: pressing Send on partial connectivity must not hang.
     *
     * Bug (homebase.log 2026-04-17 15:14:56): text-message send suspended between the
     * "encrypting" and "outbox enqueued" log lines. The only suspending step in between
     * is `OutboxSync.tryEnqueue`'s `eventBus.emit(ItemEnqueued)`. On partial connectivity
     * other EventBus subscribers (AuthConnectionCoordinator, ConnectionRequestService,
     * DriveContactService) do slow network IO synchronously inside `collect { … }`; the
     * 11-slot SharedFlow buffer fills up, and the default SUSPEND overflow parks every
     * subsequent emit — including the one from tryEnqueue — so the Send coroutine never
     * returns and the UI's Send button stays disabled.
     *
     * Contract under test: tryEnqueue must complete within a bounded time regardless of
     * whether the bus is saturated. The outbox is the durable queue; notifying listeners
     * is a best-effort side-effect that must not gate enqueue completion.
     */
    @Test
    fun testTryEnqueueDoesNotBlockOnSaturatedEventBus() {
        // NOT runOutboxTest (see class KDoc): this test deliberately blocks the test
        // thread on `withContext(Dispatchers.Default)` with a REAL withTimeout, and
        // needs the inner DB insert to make progress on a real dispatcher while the
        // virtual scheduler is parked. Binding the DB to virtual time would deadlock it.
        val db = DatabaseManager({ createInMemoryDatabase() })

        runTest {
            val eventBus = EventBus()
            val uploader = TestUploader()

            val sync = OutboxSync(
                databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
            )

            // Simulate a subscriber that blocks inside `collect { … }` the way the real
            // ConnectionRequestService / AuthConnectionCoordinator / DriveContactService
            // subscribers do when they call a suspending network fetch on partial
            // connectivity: the first event is picked up, the collect body never returns,
            // and further emissions pile up in the 65-slot buffer (replay 1 + extraBufferCapacity 64).
            val blocker = CompletableDeferred<Unit>()
            val collectorJob = backgroundScope.launch {
                eventBus.events.collect {
                    blocker.await()
                }
            }
            testScheduler.runCurrent()

            // Saturate the bus buffer. We launch each emit so emits that can't fit don't
            // suspend the test body itself — they stay parked inside their own launched
            // coroutine, leaving the bus in a "next emit will suspend" state. The count must
            // exceed the 65-slot buffer with margin; the overflow emits simply park harmlessly.
            repeat(128) { i ->
                backgroundScope.launch {
                    eventBus.emit(BackendEvent.OutboxEvent.Failed("saturate-$i"))
                }
            }
            testScheduler.runCurrent()

            // Now exercise the enqueue path. On `main` the `eventBus.emit(ItemEnqueued)`
            // inside tryEnqueue will park behind the full buffer → withTimeout fires →
            // test fails with TimeoutCancellationException. After the fix (tryEmit /
            // fire-and-forget) tryEnqueue completes promptly.
            //
            // We use a REAL-time timeout (withContext(Dispatchers.Default)) because the
            // inner DB insert hops to Dispatchers.Default.limitedParallelism(1) and
            // runTest's virtual-time auto-advance can fire the deadline before the real
            // DB work returns, which would create a false positive on the fix branch.
            val driveId = Uuid.random()
            val uniqueId = Uuid.random()
            val enqueued = withContext(Dispatchers.Default) {
                withTimeout(3.seconds) {
                    sync.tryEnqueue(
                        driveId = driveId,
                        uniqueId = uniqueId,
                        dependencyUniqueId = null,
                        priority = 1,
                        uploadType = 0,
                        json = ""
                    )
                }
            }

            assertTrue(enqueued.enqueued, "tryEnqueue should report success")
            assertEquals(1L, db.outbox.count(), "record should be durably inserted in outbox")

            // Clean up — release the blocked collector so backgroundScope can finish.
            blocker.complete(Unit)
            collectorJob.cancel()
            sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
        }
        db.closeIgnoringTeardownRace()  // real-dispatcher teardown race; see helper
    }

    /**
     * Regression: re-enqueueing a request for the same `(driveId, uniqueId)` while a stale
     * row is still retrying used to crash the edit-message flow.
     *
     * Bug (homebase.log 2026-04-18 12:09:51): after the server returned 400 on `UpdateFile(2)`,
     * the outbox kept the row for retry. A second `tryEnqueue` for the same message then hit
     * `SQLiteException: [SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed (UNIQUE constraint
     * failed: Outbox.driveId, Outbox.uniqueId)` — because the INSERT statement is a plain insert,
     * not an upsert. The exception was caught, `tryEnqueue` returned false, and
     * `ChatMessageSenderService.updateMessage` re-threw as `IllegalStateException: Failed to
     * update chat message`, which surfaced as a user-visible toast on every retry attempt.
     *
     * Contract under test: `tryEnqueue` on a duplicate `(driveId, uniqueId)` reports
     * [EnqueueResult.AlreadyQueued] (doesn't throw) and leaves the original row untouched —
     * distinguishable from a real DB failure ([EnqueueResult.Failed]). Callers that want the
     * new request to supersede the old one must use `replaceEnqueue`.
     */
    @Test
    fun testTryEnqueueDuplicateReturnsAlreadyQueuedAndKeepsOriginal() = runOutboxTest { db ->
        val eventBus = EventBus()
        val uploader = TestUploader()
        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )

        val driveId = Uuid.random()
        val uniqueId = Uuid.random()

        val first = sync.tryEnqueue(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 1,
            uploadType = 2, // UpdateFile
            json = "original"
        )
        assertEquals(EnqueueResult.Enqueued, first, "first enqueue should succeed")
        assertTrue(first.enqueued)
        assertEquals(1L, db.outbox.count())

        val second = sync.tryEnqueue(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 1,
            uploadType = 2,
            json = "superseding"
        )
        assertEquals(
            EnqueueResult.AlreadyQueued, second,
            "duplicate tryEnqueue must report AlreadyQueued — not throw, and not a generic failure"
        )
        assertFalse(second.enqueued)
        assertEquals(1L, db.outbox.count(), "original row must remain")

        val row = db.outbox.checkout()
        assertNotNull(row)
        assertEquals("original", row.json.decodeToString(), "original row's payload must be preserved")
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }

    /**
     * The strand guard in `replaceEnqueue` (see `wouldStrandPendingCreate`) must be
     * distinguishable from a DB failure: replacing a pending UploadNewFile with an
     * UpdateFile is refused with [EnqueueResult.WouldStrandCreate] and the queued
     * create stays untouched.
     */
    @Test
    fun testReplaceEnqueueRefusesToStrandPendingCreate() = runOutboxTest { db ->
        val eventBus = EventBus()
        val uploader = TestUploader()
        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )

        val driveId = Uuid.random()
        val uniqueId = Uuid.random()

        val create = sync.tryEnqueue(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 1,
            uploadType = DriveOutboxUploader.UploadNewFile,
            json = "create"
        )
        assertTrue(create.enqueued)

        val result = sync.replaceEnqueue(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 1,
            uploadType = DriveOutboxUploader.UpdateFile,
            json = "edit"
        )
        assertEquals(EnqueueResult.WouldStrandCreate, result)
        assertEquals(1L, db.outbox.count(), "the pending create must remain")

        val row = db.outbox.checkout()
        assertNotNull(row)
        assertEquals("create", row.json.decodeToString(), "the un-sent create must not be replaced")
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }

    /**
     * Regression for the fix of the above: when the caller *wants* the new request to supersede
     * the stale one (as `ChatMessageSenderService.updateMessage` does for every edit), using
     * `replaceEnqueue` must not throw, must leave exactly one row, and must replace the payload.
     */
    @Test
    fun testReplaceEnqueueSupersedesExistingRow() = runOutboxTest { db ->
        val eventBus = EventBus()
        val uploader = TestUploader()
        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )

        val driveId = Uuid.random()
        val uniqueId = Uuid.random()

        val first = sync.tryEnqueue(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 1,
            uploadType = 2,
            json = "stale"
        )
        assertTrue(first.enqueued)

        val replaced = sync.replaceEnqueue(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 1,
            uploadType = 2,
            json = "fresh"
        )
        assertEquals(EnqueueResult.Enqueued, replaced, "replaceEnqueue must succeed even when a row already exists")
        assertEquals(1L, db.outbox.count(), "exactly one row should remain after replace")

        val row = db.outbox.checkout()
        assertNotNull(row)
        assertEquals("fresh", row.json.decodeToString(), "new payload must win over the stale one")
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }

    /**
     * Regression: a `NotFoundException` from the uploader (e.g. local file no longer
     * present when DriveOutboxUploader.updateLocalMetadataContent goes to look it up)
     * is a permanent failure — must drop on the first attempt instead of burning
     * 20 retries (~48h).
     */
    @Test
    fun testPermanentFailure_NotFoundExceptionDroppedOnFirstAttempt() = runOutboxTest { db ->
        val eventBus = EventBus()
        val uploader = TestUploader()
        uploader.failureException = NotFoundException()

        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )
        sync.setOnline(true)

        val droppedDeferred = async {
            eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.OutboxItemDropped>().first()
        }
        testScheduler.runCurrent()

        val driveId = Uuid.random()
        val uniqueId = Uuid.random()
        db.outbox.insert(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 0,
            uploadType = 0,
            json = byteArrayOf(),
            filePaths = null,
        )

        try { sync.send() } catch (_: Exception) {}
        advanceUntilIdle()

        val dropped = droppedDeferred.await()

        assertEquals(0L, db.outbox.count(), "row must be dropped on first attempt")
        assertEquals(uniqueId, dropped.uniqueId)
        assertEquals(driveId, dropped.driveId)
        assertEquals(1, dropped.attempts, "drop must happen on attempt 1, not after MAX_RETRIES")
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }

    /**
     * #842: a staged payload file missing at drain time (DriveUploadProvider's
     * pre-flight throws [StagedPayloadMissingException]) is permanent — the
     * source bytes are gone, no retry can bring them back. Pre-#842 this
     * surfaced as a platform IO error wrapped into a transient "Network
     * failure" and burned 20 retries (~48h) with the message stuck.
     */
    @Test
    fun testPermanentFailure_StagedPayloadMissingDroppedOnFirstAttempt() = runOutboxTest { db ->
        val eventBus = EventBus()
        val uploader = TestUploader()
        uploader.failureException = StagedPayloadMissingException(
            path = "/data/outbox-staging/enc123.encrypted",
            payloadKey = "chat_img",
        )

        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )
        sync.setOnline(true)

        val droppedDeferred = async {
            eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.OutboxItemDropped>().first()
        }
        testScheduler.runCurrent()

        val driveId = Uuid.random()
        val uniqueId = Uuid.random()
        db.outbox.insert(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 0,
            uploadType = 0,
            json = byteArrayOf(),
            filePaths = null,
        )

        try { sync.send() } catch (_: Exception) {}
        advanceUntilIdle()

        val dropped = droppedDeferred.await()

        assertEquals(0L, db.outbox.count(), "row must be dropped on first attempt")
        assertEquals(uniqueId, dropped.uniqueId)
        assertEquals(1, dropped.attempts, "drop must happen on attempt 1, not after MAX_RETRIES")
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }

    /**
     * Regression: a `ClientException` carrying `errorCode = VersionTagMismatch`
     * (the structured server response) is permanent — drop on first attempt.
     * This locks in the existing enum-based branch in `isPermanentFailure`.
     */
    @Test
    fun testPermanentFailure_VersionTagMismatchByCodeDroppedOnFirstAttempt() = runOutboxTest { db ->
        val eventBus = EventBus()
        val uploader = TestUploader()
        uploader.failureException = clientException(
            errorCode = OdinClientErrorCode.VersionTagMismatch,
            message = "Mismatching version tag 7373d519-d042-d100-4aad-a8e5d48dd851",
        )

        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )
        sync.setOnline(true)

        val droppedDeferred = async {
            eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.OutboxItemDropped>().first()
        }
        testScheduler.runCurrent()

        val driveId = Uuid.random()
        val uniqueId = Uuid.random()
        db.outbox.insert(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 0,
            uploadType = 0,
            json = byteArrayOf(),
            filePaths = null,
        )

        try { sync.send() } catch (_: Exception) {}
        advanceUntilIdle()

        val dropped = droppedDeferred.await()

        assertEquals(0L, db.outbox.count())
        assertEquals(uniqueId, dropped.uniqueId)
        assertEquals(1, dropped.attempts)
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }

    /**
     * Regression for the actual user-reported scenario (homebase.log 2026-05-04):
     * the server collapsed `errorCode` to `UnhandledScenario` while preserving the
     * title `Mismatching version tag …`. Without a title-match fallback this loops
     * for 20 retries / ~48h. The fallback in `classifyPermanentFailure` must drop
     * on attempt 1 — and the drop must be honest: no `ItemCompleted` for an
     * update the server rejected (previously `DriveOutboxUploader.upload`
     * swallowed this case by returning normally, faking a successful send).
     */
    @Test
    fun testPermanentFailure_MismatchingVersionTagByTitleDroppedOnFirstAttempt() = runOutboxTest { db ->
        val eventBus = EventBus()
        val uploader = TestUploader()
        uploader.failureException = clientException(
            errorCode = OdinClientErrorCode.UnhandledScenario,
            message = "Mismatching version tag 7373d519-d042-d100-4aad-a8e5d48dd851",
        )

        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )
        sync.setOnline(true)

        // Collect ALL outbox events so we can assert what was NOT emitted; await
        // the terminal `Completed` before asserting (see class KDoc — a bare
        // advanceUntilIdle() can return before the worker drains).
        val events = mutableListOf<BackendEvent.OutboxEvent>()
        val collectorJob = backgroundScope.launch {
            eventBus.events.filterIsInstance<BackendEvent.OutboxEvent>().collect { events.add(it) }
        }
        val completedDeferred = async {
            eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.Completed>().first()
        }
        testScheduler.runCurrent()

        val driveId = Uuid.random()
        val uniqueId = Uuid.random()
        db.outbox.insert(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 0,
            uploadType = 0,
            json = byteArrayOf(),
            filePaths = null,
        )

        try { sync.send() } catch (_: Exception) {}
        advanceUntilIdle()

        completedDeferred.await()
        advanceUntilIdle() // let the list collector drain anything still buffered

        val dropped = events.filterIsInstance<BackendEvent.OutboxEvent.OutboxItemDropped>().single()

        assertEquals(0L, db.outbox.count())
        assertEquals(uniqueId, dropped.uniqueId)
        assertEquals(1, dropped.attempts)
        assertTrue(
            events.filterIsInstance<BackendEvent.OutboxEvent.ItemCompleted>().isEmpty(),
            "a dropped item must NOT be reported as completed",
        )

        collectorJob.cancel()
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }

    /**
     * Honest-drop contract for the self-recipient rejection: "Cannot transfer
     * to yourself" is a terminal 400 (the recipient list contains the
     * logged-in identity; the server rejects it forever).
     *
     * Previously this case was swallowed inside `DriveOutboxUploader.upload`
     * by returning normally — OutboxSync then took the success path and
     * emitted `ItemCompleted`, so the chat bubble showed *sent* for a message
     * that never reached the server. The classification now lives in
     * `classifyPermanentFailure` and the drop must be honest: row deleted on
     * attempt 1, `OutboxItemDropped` emitted, NO `ItemCompleted`, and the
     * final `Completed` batch event counts 0 sent items.
     */
    @Test
    fun testPermanentFailure_CannotTransferToYourselfDropsHonestly() = runOutboxTest { db ->
        val eventBus = EventBus()
        val uploader = TestUploader()
        uploader.failureException = clientException(
            errorCode = OdinClientErrorCode.UnhandledScenario,
            message = "Cannot transfer to yourself: frodo.dotyou.cloud",
        )

        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )
        sync.setOnline(true)

        // Collect ALL outbox events so we can assert what was NOT emitted.
        // `advanceUntilIdle()` alone is not enough to drain the worker (see the
        // class KDoc) — the established pattern in this file is to await the
        // terminal `Completed` event (emitted when the last worker exits)
        // before asserting.
        val events = mutableListOf<BackendEvent.OutboxEvent>()
        val collectorJob = backgroundScope.launch {
            eventBus.events.filterIsInstance<BackendEvent.OutboxEvent>().collect { events.add(it) }
        }
        val completedDeferred = async {
            eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.Completed>().first()
        }
        testScheduler.runCurrent()

        val driveId = Uuid.random()
        val uniqueId = Uuid.random()
        db.outbox.insert(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 0,
            uploadType = 0,
            json = byteArrayOf(),
            filePaths = null,
        )

        try { sync.send() } catch (_: Exception) {}
        advanceUntilIdle()

        val completed = completedDeferred.await()
        advanceUntilIdle() // let the list collector drain anything still buffered

        assertEquals(0L, db.outbox.count(), "row must be dropped on first attempt")

        val dropped = events.filterIsInstance<BackendEvent.OutboxEvent.OutboxItemDropped>().single()
        assertEquals(uniqueId, dropped.uniqueId)
        assertEquals(driveId, dropped.driveId)
        assertEquals(1, dropped.attempts, "drop must happen on attempt 1, not after MAX_RETRIES")
        assertNotNull(dropped.reason, "a permanent drop must carry its classifier reason")
        assertTrue(
            dropped.reason!!.contains("Cannot transfer to yourself"),
            "reason should surface the server message; was: ${dropped.reason}",
        )

        assertTrue(
            events.filterIsInstance<BackendEvent.OutboxEvent.ItemCompleted>().isEmpty(),
            "a dropped item must NOT be reported as completed — that's the fake-success bug",
        )
        assertEquals(0, completed.totalCount, "nothing was sent, so the batch count must be 0")

        collectorJob.cancel()
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }

    /**
     * Regression for the URL-preview SVG bug (homebase.log 2026-05-17 17:34:21):
     * `getLinkPreview` returned an inline SVG `data:` URI for Google Calendar.
     * `ThumbnailGenerator` wrapped the SVG bytes (1634 raw) verbatim in an
     * EmbeddedThumb, the server rejected the upload with HTTP 400
     * "Thumbnail size of 1634 exceeds 1024" (errorCode collapsed to
     * UnhandledScenario), and the outbox treated it as retryable — the
     * stuck row blocked every subsequent message in the conversation for
     * ~48h via dependency-id chaining. After the fix, the size-exceeds
     * pattern in `isPermanentFailure` drops the row on attempt 1.
     */
    @Test
    fun testPermanentFailure_ThumbnailSizeExceedsDroppedOnFirstAttempt() = runOutboxTest { db ->
        val eventBus = EventBus()
        val uploader = TestUploader()
        uploader.failureException = clientException(
            errorCode = OdinClientErrorCode.UnhandledScenario,
            message = "Thumbnail size of 1634 exceeds 1024",
        )

        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )
        sync.setOnline(true)

        val droppedDeferred = async {
            eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.OutboxItemDropped>().first()
        }
        testScheduler.runCurrent()

        val driveId = Uuid.random()
        val uniqueId = Uuid.random()
        db.outbox.insert(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 0,
            uploadType = 0,
            json = byteArrayOf(),
            filePaths = null,
        )

        try { sync.send() } catch (_: Exception) {}
        advanceUntilIdle()

        val dropped = droppedDeferred.await()

        assertEquals(0L, db.outbox.count(), "row must be dropped on first attempt")
        assertEquals(uniqueId, dropped.uniqueId)
        assertEquals(1, dropped.attempts, "drop must happen on attempt 1, not after MAX_RETRIES")
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }

    /**
     * Regression for the web video-send "stuck on Done forever" scenario
     * (PR #640 follow-up): a conversation file whose UploadNewFile recovers via
     * `DriveOutboxUploader.retryAsUpdate` is rejected by the server with HTTP 400
     * "When updating an encrypted file, the AES key must match the existing key …"
     * (errorCode collapsed to UnhandledScenario). The outbox row carries a fixed
     * key, so every retry replays the same wrong key — deterministically
     * unrecoverable. Without the title-match in `isPermanentFailure` it loops for
     * 20 retries / ~48h and dependency-chains every message behind it. After the
     * fix it drops on attempt 1.
     */
    @Test
    fun testPermanentFailure_AesKeyMismatchDroppedOnFirstAttempt() = runOutboxTest { db ->
        val eventBus = EventBus()
        val uploader = TestUploader()
        uploader.failureException = clientException(
            errorCode = OdinClientErrorCode.UnhandledScenario,
            message = "When updating an encrypted file, the AES key must match the existing key. " +
                    "Changing the AES key can invalidate existing encrypted payloads. " +
                    "If you need to rotate keys, re-upload the file instead.",
        )

        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )
        sync.setOnline(true)

        val droppedDeferred = async {
            eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.OutboxItemDropped>().first()
        }
        testScheduler.runCurrent()

        val driveId = Uuid.random()
        val uniqueId = Uuid.random()
        db.outbox.insert(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 0,
            uploadType = 0,
            json = byteArrayOf(),
            filePaths = null,
        )

        try { sync.send() } catch (_: Exception) {}
        advanceUntilIdle()

        val dropped = droppedDeferred.await()

        assertEquals(0L, db.outbox.count(), "row must be dropped on first attempt")
        assertEquals(uniqueId, dropped.uniqueId)
        assertEquals(driveId, dropped.driveId)
        assertEquals(1, dropped.attempts, "drop must happen on attempt 1, not after MAX_RETRIES")
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }

    /**
     * Regression: once the head of a dependency chain is dropped (permanent
     * failure), the next message in the chain must become eligible to
     * checkout. This locks in the SQL behavior in `OutboxQueries.kt`
     * (`WHERE … dependencyUniqueId IS NULL OR EXISTS(dep row)`).
     */
    @Test
    fun testDependencyChainUnblocksAfterPermanentFailure() = runOutboxTest { db ->
        val eventBus = EventBus()
        val uploader = TestUploader()
        uploader.failureException = clientException(
            errorCode = OdinClientErrorCode.UnhandledScenario,
            message = "Thumbnail size of 1634 exceeds 1024",
        )

        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )
        sync.setOnline(true)

        val completedDeferred = async {
            eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.Completed>()
                .first().totalCount
        }
        testScheduler.runCurrent()

        val driveId = Uuid.random()
        val stuckHead = Uuid.random()
        val dependent = Uuid.random()

        db.outbox.insert(
            driveId = driveId,
            uniqueId = stuckHead,
            dependencyUniqueId = null,
            priority = 0,
            uploadType = 0,
            json = byteArrayOf(),
            filePaths = null,
        )
        db.outbox.insert(
            driveId = driveId,
            uniqueId = dependent,
            dependencyUniqueId = stuckHead,
            priority = 0,
            uploadType = 0,
            json = byteArrayOf(),
            filePaths = null,
        )
        assertEquals(2L, db.outbox.count())

        // After head drops on attempt 1, the dependent becomes
        // eligible. Future uploader.upload() calls still throw the
        // size-exceeds exception → dependent also drops.
        try { sync.send() } catch (_: Exception) {}
        advanceUntilIdle()
        completedDeferred.await()

        assertEquals(
            0L, db.outbox.count(),
            "both stuck head and its dependent must be drained — dependent " +
                    "stays in outbox only as long as the head exists"
        )
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }

    /**
     * cancelPending contract: queued rows are removed and classified
     * (create vs other); a missing row reports NothingPending.
     */
    @Test
    fun testCancelPendingRemovesQueuedRowsAndClassifies() = runOutboxTest { db ->
        val eventBus = EventBus()
        val uploader = TestUploader()
        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )

        val driveId = Uuid.random()
        val createId = Uuid.random()
        val editId = Uuid.random()

        sync.tryEnqueue(
            driveId = driveId, uniqueId = createId, dependencyUniqueId = null,
            priority = 1, uploadType = DriveOutboxUploader.UploadNewFile, json = "create",
        )
        sync.tryEnqueue(
            driveId = driveId, uniqueId = editId, dependencyUniqueId = null,
            priority = 1, uploadType = DriveOutboxUploader.UpdateFile, json = "edit",
        )
        assertEquals(2L, db.outbox.count())

        assertEquals(CancelOutcome.CancelledCreate, sync.cancelPending(driveId, createId))
        assertEquals(CancelOutcome.Cancelled, sync.cancelPending(driveId, editId))
        assertEquals(0L, db.outbox.count(), "both queued rows must be removed")

        assertEquals(
            CancelOutcome.NothingPending, sync.cancelPending(driveId, createId),
            "a second cancel finds nothing",
        )
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }

    /**
     * cancelPending must NOT delete a checked-out row: the worker already read
     * it, so deleting it can't stop the upload — it only fakes the cancel while
     * the request still ships (the old raw-deleteBy "ghost message" race).
     * The row must survive untouched and the caller gets InFlight(isCreate).
     */
    @Test
    fun testCancelPendingRefusesInFlightRow() = runOutboxTest { db ->
        val eventBus = EventBus()
        val uploader = TestUploader()
        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )

        val driveId = Uuid.random()
        val uniqueId = Uuid.random()
        db.outbox.insert(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 0,
            uploadType = DriveOutboxUploader.UploadNewFile,
            json = byteArrayOf(),
            filePaths = null,
        )
        // Simulate a worker holding the row.
        val checkedOut = db.outbox.checkout()
        assertNotNull(checkedOut)

        val outcome = sync.cancelPending(driveId, uniqueId)
        assertEquals(CancelOutcome.InFlight(isCreate = true), outcome)

        val row = db.outbox.selectByDriveAndUnique(driveId, uniqueId)
        assertNotNull(row, "in-flight row must survive a cancel attempt")
        assertNotNull(row.checkOutStamp, "row must remain checked out")
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }

    /**
     * Cancelling the outbox scope mid-upload (logout, shutdown) must NOT be
     * recorded as a failed attempt: no `ItemFailed`/`Failed`/`OutboxItemDropped`
     * events, no `checkInFailed` (checkOutCount stays 0). The row stays checked
     * out and is recovered by the next start's `clearCheckedOut` — exactly like
     * an app kill. Locks in the CancellationException rethrow at the top of
     * `outboxSend`'s catch.
     */
    @Test
    fun testScopeCancellationMidUploadIsNotAFailedAttempt() = runOutboxTest { db ->
        val eventBus = EventBus()
        // A dedicated, cancellable scope on the same virtual scheduler.
        val workerScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())

        val uploadStarted = CompletableDeferred<Unit>()
        val uploader = object : OutboxUploader {
            override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) {
                uploadStarted.complete(Unit)
                kotlinx.coroutines.delay(600_000) // parked until the scope is cancelled
                error("unreachable — upload should have been cancelled")
            }
        }

        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = workerScope
        )
        sync.setOnline(true)

        val events = mutableListOf<BackendEvent.OutboxEvent>()
        val collectorJob = backgroundScope.launch {
            eventBus.events.filterIsInstance<BackendEvent.OutboxEvent>().collect { events.add(it) }
        }
        testScheduler.runCurrent()

        val driveId = Uuid.random()
        val uniqueId = Uuid.random()
        db.outbox.insert(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 0,
            uploadType = 0,
            json = byteArrayOf(),
            filePaths = null,
        )

        sync.send()
        uploadStarted.await() // upload is now parked inside delay()

        workerScope.cancel()
        advanceUntilIdle()

        assertTrue(
            events.filterIsInstance<BackendEvent.OutboxEvent.ItemFailed>().isEmpty(),
            "cancellation must not be reported as an upload failure",
        )
        assertTrue(
            events.filterIsInstance<BackendEvent.OutboxEvent.Failed>().isEmpty(),
            "cancellation must not be reported as an upload failure",
        )
        assertTrue(
            events.filterIsInstance<BackendEvent.OutboxEvent.OutboxItemDropped>().isEmpty(),
            "cancellation must never drop the row",
        )

        val row = db.outbox.selectByDriveAndUnique(driveId, uniqueId)
        assertNotNull(row, "row must survive cancellation")
        assertEquals(0L, row.checkOutCount, "cancellation must not count as a failed attempt")
        assertNotNull(
            row.checkOutStamp,
            "row stays checked out — the next start's clearCheckedOut recovers it",
        )

        collectorJob.cancel()
        // No sync.clearCheckout() here: the worker scope is dead, so activeThreads
        // never decremented — clearCheckout would just spin to its timeout.
    }

    @Test
    fun testEmptyOutbox() = runOutboxTest { db ->
        val eventBus = EventBus()  // Fresh instance per test

        val uploader = TestUploader()

        val sync = OutboxSync(
            databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
        )
        sync.setOnline(true)

        val completedDeferred = async {
            eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.Completed>()
                .first().totalCount
        }
        testScheduler.runCurrent() // Kick off the async collector

        val started = sync.send()
        assertTrue(started)  // Starts thread but finds no work

        advanceUntilIdle()
        val completedCount = completedDeferred.await()

        assertEquals(0, completedCount)
        assertEquals(0, uploader.uploaded.size)
        sync.clearCheckout(timeoutMs = 5_000)  // drain before db.close(); see file kdoc
    }
}