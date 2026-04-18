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
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class TestUploader : OutboxUploader {
    var shouldFail = false
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


@OptIn(ExperimentalCoroutinesApi::class)
class OutboxSyncTest {


    @Test
    fun testSuccessfulSend() {
        val db = DatabaseManager { createInMemoryDatabase() }

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
        val db = DatabaseManager { createInMemoryDatabase() }

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
        val db = DatabaseManager { createInMemoryDatabase() }

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
        val db = DatabaseManager { createInMemoryDatabase() }

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
        val db = DatabaseManager { createInMemoryDatabase() }

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

    @Test
    fun testEmptyOutbox() {
        val db = DatabaseManager { createInMemoryDatabase() }

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