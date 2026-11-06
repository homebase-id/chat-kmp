package id.homebase.api.sync.database

import id.homebase.api.client.drives.KeyHeaderTransform
import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.drives.files.TransformKeyHeadersOutboxRequest
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.crypto.EncryptedKeyHeader
import id.homebase.api.crypto.EncryptionType
import id.homebase.api.serialization.OutboxSerializer
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

class OutboxKeyHeaderTransformsTest {

    private fun run(body: suspend TestScope.(DatabaseManager, OutboxSync) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val db = DatabaseManager({ createInMemoryDatabase() }, dispatcher = dispatcher, readDispatcher = dispatcher)
        try {
            body(db, OutboxSync(db, TestUploader(), EventBus(), backgroundScope))
        } finally {
            db.close()
        }
    }

    private val drive = Uuid.random()

    private fun transform(fileId: Uuid = Uuid.random(), versionTag: Uuid = Uuid.random()) =
        KeyHeaderTransform(fileId, versionTag, EncryptedKeyHeader(type = EncryptionType.ZkDrive, iv = ByteArray(16), encryptedAesKey = ByteArray(48)))

    private suspend fun DatabaseManager.waiting(): List<TransformKeyHeadersOutboxRequest> =
        outbox.selectPendingByDriveAndType(drive, DriveOutboxUploader.TransformKeyHeaders)
            .map { OutboxSerializer.decode<TransformKeyHeadersOutboxRequest>(it) }

    @Test
    fun aDrivesTransformsWaitInOneLowPriorityRow() = run { db, sync ->
        val a = transform()
        val b = transform()
        sync.enqueueKeyHeaderTransforms(drive, listOf(a), sendNow = false)
        sync.enqueueKeyHeaderTransforms(drive, listOf(b), sendNow = false)

        assertEquals(listOf(listOf(a.fileId, b.fileId)), db.waiting().map { r -> r.files.map { it.fileId } })
        val row = db.outbox.selectPendingByDriveAndType(drive, DriveOutboxUploader.TransformKeyHeaders).single()
        assertEquals(OutboxSync.KEY_HEADER_TRANSFORM_PRIORITY, row.priority)
    }

    @Test
    fun aFileQueuedAgainKeepsOnlyItsNewestTransform() = run { db, sync ->
        val file = Uuid.random()
        val other = transform()
        sync.enqueueKeyHeaderTransforms(drive, listOf(transform(file), other), sendNow = false)
        val newer = transform(file)
        sync.enqueueKeyHeaderTransforms(drive, listOf(newer), sendNow = false)

        val files = db.waiting().single().files
        assertEquals(listOf(other.fileId, file), files.map { it.fileId })
        assertEquals(newer.versionTag, files.last().versionTag)
    }

    @Test
    fun aRowHoldsAtMostOneRequestsWorth() = run { db, sync ->
        sync.enqueueKeyHeaderTransforms(drive, List(OutboxSync.KEY_HEADER_TRANSFORMS_PER_ROW + 1) { transform() }, sendNow = false)

        assertEquals(listOf(OutboxSync.KEY_HEADER_TRANSFORMS_PER_ROW, 1), db.waiting().map { it.files.size })
    }

    @Test
    fun aRowBeingSentIsLeftAloneAndNewOnesWaitInAnother() = run { db, sync ->
        val sending = transform()
        sync.enqueueKeyHeaderTransforms(drive, listOf(sending), sendNow = false)
        val inFlight = assertNotNull(db.outbox.checkout())
        val later = transform(sending.fileId)
        sync.enqueueKeyHeaderTransforms(drive, listOf(later), sendNow = false)

        assertEquals(listOf(sending.fileId), OutboxSerializer.decode<TransformKeyHeadersOutboxRequest>(inFlight).files.map { it.fileId })
        assertEquals(listOf(later.versionTag), db.waiting().single().files.map { it.versionTag })
        assertEquals(2L, db.outbox.count())
    }
}
