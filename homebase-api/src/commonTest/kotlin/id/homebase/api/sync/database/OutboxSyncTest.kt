package id.homebase.api.sync.database

import co.touchlab.kermit.Logger
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*
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
                outboxRecord.driveId, outboxRecord.fileId, 0.5F
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
                databaseManager = db, uploader = uploader, eventBus = eventBus, scope = this
            )

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
            assertEquals(fileId, uploader.uploaded[0].fileId)
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
                databaseManager = db, uploader = uploader, eventBus = eventBus, scope = this
            )

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
                databaseManager = db, uploader = uploader, eventBus = eventBus, scope = this
            )

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
    fun testEmptyOutbox() {
        val db = DatabaseManager { createInMemoryDatabase() }

        runTest {
            val eventBus = EventBus()  // Fresh instance per test

            val uploader = TestUploader()

            val sync = OutboxSync(
                databaseManager = db, uploader = uploader, eventBus = eventBus, scope = this
            )

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