package id.homebase.api.sync.database

import id.homebase.api.client.drives.FileDecryptionException
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** An upload to a private drive this device has no key for yet waits for the key, however long, instead of dropping. */
class OutboxNoDriveKeyTest {
    private val driveId = Uuid.random()
    private val eventBus = EventBus()

    private class LockedDriveUploader : OutboxUploader {
        override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) {
            throw FileDecryptionException.NoDriveKey(outboxRecord.driveId)
        }
    }

    @Test
    fun anUploadWithoutTheDriveKeyWaitsUncharged() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        val page = Uuid.random()
        runTest {
            val sync = OutboxSync(databaseManager = db, uploader = LockedDriveUploader(), eventBus = eventBus, scope = backgroundScope)
            sync.setOnline(true)
            db.outbox.insert(driveId, page, null, priority = 1, uploadType = DriveOutboxUploader.UploadNewFile,
                json = byteArrayOf(), filePaths = null)

            val completed = async { eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.Completed>().first() }
            testScheduler.runCurrent()
            sync.send()
            advanceUntilIdle()
            completed.await()

            assertEquals(0L, db.outbox.selectByUniqueId(page)?.checkOutCount, "still queued, no attempt charged")
            sync.clearCheckout(timeoutMs = 5_000)
        }
        db.closeIgnoringTeardownRace()
    }
}
