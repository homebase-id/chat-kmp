package id.homebase.api.sync.database

import id.homebase.api.client.drives.files.DriveOutboxUploader
import id.homebase.api.client.eventbus.EventBus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Message Info's blocker reporting + the "Try now resolves the chain" heal:
 * [OutboxSync.blockingRowSnapshot] (walk to the chain head),
 * [OutboxSync.pendingRowSnapshot] (now exposes checkOutStamp), and
 * [OutboxSync.runNowResolvingDependencies] (reset the whole blocked chain).
 */
class OutboxResolveDependenciesTest {

    private fun outboxTest(body: suspend TestScope.(DatabaseManager) -> Unit) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val db = DatabaseManager({ createInMemoryDatabase() }, dispatcher = d, readDispatcher = d)
        try {
            body(db)
        } finally {
            db.close()
        }
    }

    private fun TestScope.sync(db: DatabaseManager) =
        OutboxSync(databaseManager = db, uploader = TestUploader(), eventBus = EventBus(), scope = backgroundScope)

    private suspend fun DatabaseManager.insertRow(drive: Uuid, id: Uuid, dep: Uuid?) =
        outbox.insert(
            driveId = drive,
            uniqueId = id,
            dependencyUniqueId = dep,
            priority = 0,
            uploadType = DriveOutboxUploader.UploadNewFile,
            json = byteArrayOf(),
            filePaths = null,
        )

    @Test
    fun blockingRowSnapshotWalksToTheChainHead() = outboxTest { db ->
        val drive = Uuid.random()
        val head = Uuid.random()
        val mid = Uuid.random()
        val tail = Uuid.random()
        db.insertRow(drive, head, dep = null)
        db.insertRow(drive, mid, dep = head)
        db.insertRow(drive, tail, dep = mid)
        val s = sync(db)

        // The deepest still-pending ancestor (the head) is what determines when
        // `tail` unblocks, so that's what Message Info reports.
        val blocker = s.blockingRowSnapshot(drive, tail)
        assertNotNull(blocker, "a blocked tail must report its chain head")
        assertNull(blocker.dependencyUniqueId, "the head has no dependency of its own")

        val tailSnap = assertNotNull(s.pendingRowSnapshot(drive, tail))
        assertTrue(tailSnap.dependencyPending, "tail is blocked: its dependency row still exists")
    }

    @Test
    fun blockingRowSnapshotIsNullWhenNothingBlocks() = outboxTest { db ->
        val drive = Uuid.random()
        val lone = Uuid.random()
        db.insertRow(drive, lone, dep = null)
        val s = sync(db)
        assertNull(s.blockingRowSnapshot(drive, lone), "no dependency → no blocker")
    }

    @Test
    fun checkedOutWithoutLiveWorkerReadsAsZombieNotUploading() = outboxTest { db ->
        // The exact "in-flight vs dead" signal — no time guess. A row checked out
        // in the DB but with NO live upload worker holding its stamp (here: a
        // direct checkout, not via the send worker; in production: a worker that
        // died / a killed process that emptied the live set) is a zombie.
        val drive = Uuid.random()
        val id = Uuid.random()
        db.insertRow(drive, id, dep = null)
        val s = sync(db)

        val before = assertNotNull(s.pendingRowSnapshot(drive, id))
        assertFalse(before.isCheckedOut)
        assertFalse(before.isActivelyUploading)

        assertEquals(id, assertNotNull(db.outbox.checkout()).uniqueId)

        val snap = assertNotNull(s.pendingRowSnapshot(drive, id))
        assertTrue(snap.isCheckedOut, "checked out in the DB")
        assertFalse(snap.isActivelyUploading, "but no live worker holds it → a zombie, not in-flight")
    }

    @Test
    fun runNowResolvingDependenciesResetsTheWholeChain() = outboxTest { db ->
        val drive = Uuid.random()
        val head = Uuid.random()
        val mid = Uuid.random()
        val tail = Uuid.random()
        db.insertRow(drive, head, dep = null)
        db.insertRow(drive, mid, dep = head)
        db.insertRow(drive, tail, dep = mid)
        // Park the whole chain far in the future (deep backoff).
        val future = 9_999_999_999_999L
        db.outbox.setNextRunTime(drive, head, future)
        db.outbox.setNextRunTime(drive, mid, future)
        db.outbox.setNextRunTime(drive, tail, future)
        val s = sync(db)

        val changed = s.runNowResolvingDependencies(drive, tail)
        advanceUntilIdle()

        assertTrue(changed, "resolving must reset at least one row")
        for (id in listOf(head, mid, tail)) {
            assertEquals(
                0L,
                assertNotNull(db.outbox.selectByDriveAndUnique(drive, id)).nextRunTime,
                "every still-pending ancestor + self must be reset to run immediately",
            )
        }
    }
}
