package id.homebase.api.sync.database

import id.homebase.api.client.drives.KeyHeaderTransform
import id.homebase.api.client.drives.SealedKeyHeader
import id.homebase.api.client.drives.SealedKeyHeaders
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.TransformKeyHeadersOutboxRequest
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.crypto.EncryptionType
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.serialization.OutboxSerializer
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlin.time.Duration.Companion.seconds
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

class KeyHeaderTransformQueueTest {

    private fun run(body: suspend TestScope.(DatabaseManager, KeyHeaderTransformQueue) -> Unit) =
        runOutboxSyncTest { db, sync -> body(db, KeyHeaderTransformQueue(sync, db, events, backgroundScope)) }

    @AfterTest
    fun noSink() {
        SealedKeyHeaders.sink = null
    }

    private val drive = Uuid.random()
    private val events = EventBus()

    private fun transform(fileId: Uuid = Uuid.random(), versionTag: Uuid = Uuid.random()) =
        KeyHeaderTransform(fileId, versionTag, EncryptedKeyHeader(type = EncryptionType.ZkDrive, iv = ByteArray(16), encryptedAesKey = ByteArray(48)))

    private suspend fun DatabaseManager.waiting(): List<TransformKeyHeadersOutboxRequest> =
        outbox.selectUntriedByDriveAndType(drive, DriveOutboxUploader.TransformKeyHeaders)
            .map { OutboxSerializer.decode<TransformKeyHeadersOutboxRequest>(it) }

    @Test
    fun filesArrivingTogetherWaitInOneLowPriorityRowAndAreQueuedOnce() = run { db, _ ->
        val a = transform()
        val b = transform()
        val sink = assertNotNull(SealedKeyHeaders.sink)
        listOf(a, b, a).forEach { sink(SealedKeyHeader(drive, it)) }
        // advanceUntilIdle doesn't wait for background work: the queue's wait has to be stepped over.
        advanceTimeBy(5.seconds)
        sink(SealedKeyHeader(drive, a))
        advanceTimeBy(5.seconds)

        assertEquals(listOf(setOf(a.fileId, b.fileId)), db.waiting().map { r -> r.files.map { it.fileId }.toSet() })
        val row = db.outbox.selectUntriedByDriveAndType(drive, DriveOutboxUploader.TransformKeyHeaders).single()
        assertEquals(KeyHeaderTransformQueue.PRIORITY, row.priority)
    }

    @Test
    fun afterLogoutTheSameFilesAreQueuedAgain() = run { db, _ ->
        val a = transform()
        val sink = assertNotNull(SealedKeyHeaders.sink)
        sink(SealedKeyHeader(drive, a))
        advanceTimeBy(5.seconds)
        db.outbox.deleteAll()
        events.emit(BackendEvent.SessionEnded)
        advanceTimeBy(1.seconds)
        sink(SealedKeyHeader(drive, a))
        advanceTimeBy(5.seconds)

        assertEquals(listOf(listOf(a.fileId)), db.waiting().map { r -> r.files.map { it.fileId } })
    }

    @Test
    fun aRowRetriedWhileOfflineKeepsItsBackoffToo() = run { db, queue ->
        queue.enqueue(drive, listOf(transform()), sendNow = false)
        val row = assertNotNull(db.outbox.checkout())
        db.outbox.checkInUncharged(row.checkOutStamp!!, nextRunTime = Long.MAX_VALUE)
        queue.enqueue(drive, listOf(transform()), sendNow = false)

        assertEquals(Long.MAX_VALUE, assertNotNull(db.outbox.selectByDriveAndUnique(drive, row.uniqueId)).nextRunTime)
        assertEquals(2L, db.outbox.count())
    }

    @Test
    fun aFileQueuedAgainKeepsOnlyItsNewestTransform() = run { db, queue ->
        val file = Uuid.random()
        val other = transform()
        queue.enqueue(drive, listOf(transform(file), other), sendNow = false)
        val newer = transform(file)
        queue.enqueue(drive, listOf(newer), sendNow = false)

        val files = db.waiting().single().files
        assertEquals(setOf(other.fileId, file), files.map { it.fileId }.toSet())
        assertEquals(newer.versionTag, files.single { it.fileId == file }.versionTag)
    }

    @Test
    fun aRowHoldsAtMostOneRequestsWorth() = run { db, queue ->
        queue.enqueue(drive, List(KeyHeaderTransformQueue.PER_ROW + 1) { transform() }, sendNow = false)

        assertEquals(listOf(KeyHeaderTransformQueue.PER_ROW, 1), db.waiting().map { it.files.size })
    }

    @Test
    fun aRowBeingSentIsLeftAloneAndNewOnesWaitInAnother() = run { db, queue ->
        val sending = transform()
        queue.enqueue(drive, listOf(sending), sendNow = false)
        val inFlight = assertNotNull(db.outbox.checkout())
        val later = transform(sending.fileId)
        queue.enqueue(drive, listOf(later), sendNow = false)

        assertEquals(listOf(sending.fileId), OutboxSerializer.decode<TransformKeyHeadersOutboxRequest>(inFlight).files.map { it.fileId })
        assertEquals(listOf(later.versionTag), db.waiting().single().files.map { it.versionTag })
        assertEquals(2L, db.outbox.count())
    }

    @Test
    fun aRowThatFailedKeepsItsBackoffAndNewFilesWaitBesideIt() = run { db, queue ->
        val failed = transform()
        queue.enqueue(drive, listOf(failed), sendNow = false)
        val row = assertNotNull(db.outbox.checkout())
        db.outbox.checkInFailed(row.checkOutStamp!!, nextRunTime = Long.MAX_VALUE)
        queue.enqueue(drive, listOf(transform()), sendNow = false)

        val kept = assertNotNull(db.outbox.selectByDriveAndUnique(drive, row.uniqueId))
        assertEquals(1L, kept.checkOutCount)
        assertEquals(Long.MAX_VALUE, kept.nextRunTime)
        assertEquals(2L, db.outbox.count())
    }
}
