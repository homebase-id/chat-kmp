package id.homebase.api.sync.database

import id.homebase.api.client.drives.upload.UpdateLocalAppdataContentOutboxRequest
import id.homebase.api.serialization.OutboxSerializer
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class OutboxEnqueueLatestTest {

    private fun run(body: suspend TestScope.(DatabaseManager, OutboxSync) -> Unit) = runOutboxSyncTest(body)

    private val drive = Uuid.random()
    private val file = Uuid.random()
    private val keyA = file
    private val keyB = OutboxSync.localAppDataFollowUpKey(file)

    private fun req(content: String) = UpdateLocalAppdataContentOutboxRequest(
        driveId = drive,
        fileId = file,
        versionTag = null,
        content = content,
    )

    private suspend fun contentOf(db: DatabaseManager, key: Uuid): String? =
        db.outbox.selectByDriveAndUnique(drive, key)?.let {
            OutboxSerializer.decode<UpdateLocalAppdataContentOutboxRequest>(it).content
        }

    @Test
    fun emptyGoesToSlotA() = run { db, sync ->
        sync.enqueueLatest(req("1"), sendNow = false)
        assertEquals("1", contentOf(db, keyA))
        assertNull(contentOf(db, keyB))
    }

    @Test
    fun pendingSlotAIsReplacedInPlace() = run { db, sync ->
        sync.enqueueLatest(req("1"), sendNow = false)
        sync.enqueueLatest(req("2"), sendNow = false)
        assertEquals("2", contentOf(db, keyA))
        assertNull(contentOf(db, keyB))
        assertEquals(1L, db.outbox.count())
    }

    @Test
    fun inFlightSlotAPutsNewerWriteInBBehindIt() = run { db, sync ->
        sync.enqueueLatest(req("1"), sendNow = false)
        assertNotNull(db.outbox.checkout())
        sync.enqueueLatest(req("2"), sendNow = false)
        assertEquals("1", contentOf(db, keyA))
        assertEquals("2", contentOf(db, keyB))
        assertEquals(keyA, db.outbox.selectByDriveAndUnique(drive, keyB)!!.dependencyUniqueId)
    }

    @Test
    fun inFlightAWithPendingBReplacesB() = run { db, sync ->
        sync.enqueueLatest(req("1"), sendNow = false)
        assertNotNull(db.outbox.checkout())
        sync.enqueueLatest(req("2"), sendNow = false)
        sync.enqueueLatest(req("3"), sendNow = false)
        assertEquals("1", contentOf(db, keyA))
        assertEquals("3", contentOf(db, keyB))
        assertEquals(2L, db.outbox.count())
    }

    @Test
    fun bothInFlightInsertsNothing() = run { db, sync ->
        sync.enqueueLatest(req("1"), sendNow = false)
        db.outbox.insert(drive, keyB, null, 0L, 0L, OutboxSerializer.serialize(req("2")).encodeToByteArray(), null)
        assertNotNull(db.outbox.checkout())
        assertNotNull(db.outbox.checkout())
        val result = sync.enqueueLatest(req("3"), sendNow = false)
        assertEquals(EnqueueResult.AlreadyQueued, result)
        assertEquals("1", contentOf(db, keyA))
        assertEquals("2", contentOf(db, keyB))
        assertEquals(2L, db.outbox.count())
    }

    @Test
    fun replacingAPendingRowKeepsItsOrderingDependency() = run { db, sync ->
        val tagWrite = Uuid.random()
        sync.enqueueLatest(req("1"), dependencyUniqueId = tagWrite, sendNow = false)
        sync.enqueueLatest(req("2"), sendNow = false)
        assertEquals("2", contentOf(db, keyA))
        assertEquals(tagWrite, db.outbox.selectByDriveAndUnique(drive, keyA)!!.dependencyUniqueId)
    }
}
