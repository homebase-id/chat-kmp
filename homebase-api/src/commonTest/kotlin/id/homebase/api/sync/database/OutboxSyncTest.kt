package id.homebase.api.sync.database

import co.touchlab.kermit.Logger
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

class TestUploader : OutboxUploader {
    var shouldFail = false
    var failureException: Throwable? = null
    val uploaded = mutableListOf<Outbox>()

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

        uploaded.add(outboxRecord)
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


@OptIn(ExperimentalCoroutinesApi::class)
class OutboxSyncTest {


    @Test
    fun testSuccessfulSend() {
        val db = newTestDatabaseManager()

        runTest {
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
        }
        db.close()
    }

    @Test
    fun testFailureAndRetry() {
        val db = newTestDatabaseManager()

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
        }
        db.close()
    }

    @Test
    fun testConcurrencyLimit() {
        val db = newTestDatabaseManager()

        runTest {
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
        }
        db.close()
    }

    @Test
    fun testMaxRetriesDrop() {
        val db = newTestDatabaseManager()

        runTest {
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
        }
        db.close()
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
        val db = newTestDatabaseManager()

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
            // and further emissions pile up in the 11-slot buffer.
            val blocker = CompletableDeferred<Unit>()
            val collectorJob = backgroundScope.launch {
                eventBus.events.collect {
                    blocker.await()
                }
            }
            testScheduler.runCurrent()

            // Saturate the bus buffer. We launch each emit so emits that can't fit don't
            // suspend the test body itself — they stay parked inside their own launched
            // coroutine, leaving the bus in a "next emit will suspend" state.
            repeat(20) { i ->
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

            assertTrue(enqueued, "tryEnqueue should report success")
            assertEquals(1L, db.outbox.count(), "record should be durably inserted in outbox")

            // Clean up — release the blocked collector so backgroundScope can finish.
            blocker.complete(Unit)
            collectorJob.cancel()
        }
        db.close()
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
     * Contract under test: `tryEnqueue` on a duplicate `(driveId, uniqueId)` reports failure
     * (doesn't throw) and leaves the original row untouched. Callers that want the new request
     * to supersede the old one must use `replaceEnqueue`.
     */
    @Test
    fun testTryEnqueueDuplicateReturnsFalseAndKeepsOriginal() {
        val db = newTestDatabaseManager()

        runTest {
            val eventBus = EventBus()
            val uploader = TestUploader()
            val sync = OutboxSync(
                databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
            )

            val driveId = Uuid.random()
            val uniqueId = Uuid.random()

            val firstOk = sync.tryEnqueue(
                driveId = driveId,
                uniqueId = uniqueId,
                dependencyUniqueId = null,
                priority = 1,
                uploadType = 2, // UpdateFile
                json = "original"
            )
            assertTrue(firstOk, "first enqueue should succeed")
            assertEquals(1L, db.outbox.count())

            val secondOk = sync.tryEnqueue(
                driveId = driveId,
                uniqueId = uniqueId,
                dependencyUniqueId = null,
                priority = 1,
                uploadType = 2,
                json = "superseding"
            )
            assertFalse(secondOk, "duplicate tryEnqueue should report failure, not throw")
            assertEquals(1L, db.outbox.count(), "original row must remain")

            val row = db.outbox.checkout()
            assertNotNull(row)
            assertEquals("original", row.json.decodeToString(), "original row's payload must be preserved")
        }
        db.close()
    }

    /**
     * Regression for the fix of the above: when the caller *wants* the new request to supersede
     * the stale one (as `ChatMessageSenderService.updateMessage` does for every edit), using
     * `replaceEnqueue` must not throw, must leave exactly one row, and must replace the payload.
     */
    @Test
    fun testReplaceEnqueueSupersedesExistingRow() {
        val db = newTestDatabaseManager()

        runTest {
            val eventBus = EventBus()
            val uploader = TestUploader()
            val sync = OutboxSync(
                databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope
            )

            val driveId = Uuid.random()
            val uniqueId = Uuid.random()

            val firstOk = sync.tryEnqueue(
                driveId = driveId,
                uniqueId = uniqueId,
                dependencyUniqueId = null,
                priority = 1,
                uploadType = 2,
                json = "stale"
            )
            assertTrue(firstOk)

            val replacedOk = sync.replaceEnqueue(
                driveId = driveId,
                uniqueId = uniqueId,
                dependencyUniqueId = null,
                priority = 1,
                uploadType = 2,
                json = "fresh"
            )
            assertTrue(replacedOk, "replaceEnqueue must succeed even when a row already exists")
            assertEquals(1L, db.outbox.count(), "exactly one row should remain after replace")

            val row = db.outbox.checkout()
            assertNotNull(row)
            assertEquals("fresh", row.json.decodeToString(), "new payload must win over the stale one")
        }
        db.close()
    }

    /**
     * Regression: a `NotFoundException` from the uploader (e.g. local file no longer
     * present when DriveOutboxUploader.updateLocalMetadataContent goes to look it up)
     * is a permanent failure — must drop on the first attempt instead of burning
     * 20 retries (~48h).
     */
    @Test
    fun testPermanentFailure_NotFoundExceptionDroppedOnFirstAttempt() {
        val db = newTestDatabaseManager()

        runTest {
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
        }
        db.close()
    }

    /**
     * Regression: a `ClientException` carrying `errorCode = VersionTagMismatch`
     * (the structured server response) is permanent — drop on first attempt.
     * This locks in the existing enum-based branch in `isPermanentFailure`.
     */
    @Test
    fun testPermanentFailure_VersionTagMismatchByCodeDroppedOnFirstAttempt() {
        val db = newTestDatabaseManager()

        runTest {
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
        }
        db.close()
    }

    /**
     * Regression for the actual user-reported scenario (homebase.log 2026-05-04):
     * the server collapsed `errorCode` to `UnhandledScenario` while preserving the
     * title `Mismatching version tag …`. Without a title-match fallback this loops
     * for 20 retries / ~48h. The fallback in `isPermanentFailure` (and the
     * symmetrical fallback in `DriveOutboxUploader.upload`) must drop on attempt 1.
     */
    @Test
    fun testPermanentFailure_MismatchingVersionTagByTitleDroppedOnFirstAttempt() {
        val db = newTestDatabaseManager()

        runTest {
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
        }
        db.close()
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
    fun testPermanentFailure_ThumbnailSizeExceedsDroppedOnFirstAttempt() {
        val db = newTestDatabaseManager()

        runTest {
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
        }
        db.close()
    }

    /**
     * Regression: once the head of a dependency chain is dropped (permanent
     * failure), the next message in the chain must become eligible to
     * checkout. This locks in the SQL behavior in `OutboxQueries.kt`
     * (`WHERE … dependencyUniqueId IS NULL OR EXISTS(dep row)`).
     */
    @Test
    fun testDependencyChainUnblocksAfterPermanentFailure() {
        val db = newTestDatabaseManager()

        runTest {
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
        }
        db.close()
    }

    @Test
    fun testEmptyOutbox() {
        val db = newTestDatabaseManager()

        runTest {
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
        }
        db.close()
    }
}