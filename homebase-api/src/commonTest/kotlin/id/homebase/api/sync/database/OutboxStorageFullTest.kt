@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package id.homebase.api.sync.database

import id.homebase.api.client.StorageFullException
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Refuses [full] rows with a 507, as the server does a payload upload while the identity is out of storage */
private class StorageFullUploader(private val full: Set<Uuid>) : OutboxUploader {
    private val _uploaded = atomic<List<Uuid>>(emptyList())
    val uploaded: List<Uuid> get() = _uploaded.value

    override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) {
        if (outboxRecord.uniqueId in full) throw StorageFullException(retryAfter = 1.hours, problem = null)
        _uploaded.update { it + outboxRecord.uniqueId }
    }
}

/**
 * A message held because storage is full must not hold back the messages chained after it for ordering: a text
 * after a refused photo still sends. An edit or a reaction on the held message keeps waiting for it.
 */
class OutboxStorageFullTest {
    private val driveId = Uuid.random()
    private val photo = Uuid.random()

    @Test
    fun aMessageQueuedBehindAHeldPhotoStillSends_butAReactionOnItWaits() = withSync { db, sync, uploader ->
        val text = Uuid.random()
        val reaction = Uuid.random()
        insert(db, photo, dependency = null, DriveOutboxUploader.UploadNewFile)
        insert(db, text, dependency = photo, DriveOutboxUploader.UploadNewFile)
        insert(db, reaction, dependency = photo, DriveOutboxUploader.ToggleReaction)

        drain(sync)

        assertEquals(listOf(text), uploader.uploaded)
        assertEquals(mapOf(photo to true, text to false, reaction to true), queued(db, photo, text, reaction))
    }

    @Test
    fun aMessageQueuedAfterThePhotoWasRefusedStillSends() = withSync { db, sync, uploader ->
        insert(db, photo, dependency = null, DriveOutboxUploader.UploadNewFile)
        drain(sync)
        assertEquals(emptyList(), uploader.uploaded)

        val text = Uuid.random()
        sync.tryEnqueue(driveId, text, dependencyUniqueId = photo, priority = 1,
            uploadType = DriveOutboxUploader.UploadNewFile, json = "")
        assertEquals(null, db.outbox.selectByUniqueId(text)?.dependencyUniqueId, "queued past the held photo")

        // The outbox may still be parked on the photo's retry, so wait for the text rather than for a drain to end
        sync.send()
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { while (text !in uploader.uploaded) delay(20) }
        }

        assertEquals(listOf(text), uploader.uploaded)
        assertEquals(mapOf(photo to true, text to false), queued(db, photo, text))
    }

    // Real-thread DB dispatchers, like OutboxSyncTest.testFailureAndRetry: the held row is due again in an hour,
    // which advanceUntilIdle must park on rather than run through
    private val eventBus = EventBus()

    private fun withSync(body: suspend TestScope.(DatabaseManager, OutboxSync, StorageFullUploader) -> Unit) {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val uploader = StorageFullUploader(setOf(photo))
            val sync = OutboxSync(databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope)
            sync.setOnline(true)
            body(db, sync, uploader)
            sync.clearCheckout(timeoutMs = 5_000)
        }
        db.closeIgnoringTeardownRace()
    }

    private suspend fun TestScope.drain(sync: OutboxSync) {
        val completed = async { eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.Completed>().first() }
        testScheduler.runCurrent()
        sync.send()
        advanceUntilIdle()
        completed.await()
    }

    private suspend fun insert(db: DatabaseManager, uniqueId: Uuid, dependency: Uuid?, uploadType: Long) {
        db.outbox.insert(driveId, uniqueId, dependency, priority = 1, uploadType = uploadType, json = byteArrayOf(),
            filePaths = null)
    }

    private suspend fun queued(db: DatabaseManager, vararg ids: Uuid): Map<Uuid, Boolean> =
        ids.associateWith { db.outbox.existsByUniqueId(it) }
}
