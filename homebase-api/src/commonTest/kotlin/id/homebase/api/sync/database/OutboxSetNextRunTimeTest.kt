package id.homebase.api.sync.database

import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.time.UnixTimeUtc
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The outbox `setNextRunTime` primitive behind [OutboxSync.runNow] ("Try now"),
 * plus regressions for the two stacked bugs that meant in-session retry/backoff
 * never actually ran:
 *
 * 1. **Swapped checkInFailed arguments** — the generated query's parameter
 *    order is (nextRunTime, checkOutStamp); the wrapper passed them positionally
 *    reversed (both Long, so it compiled). The WHERE never matched: failed rows
 *    stayed checked out (zombies) until the next app start's `clearCheckedOut`.
 * 2. **Seconds-vs-ms deadline** — the OutboxSync call site stored
 *    `.seconds` while `checkout` compares an ms epoch, so even a matching
 *    check-in would have made the row immediately re-eligible.
 */
class OutboxSetNextRunTimeTest {

    private suspend fun insertRow(dbm: DatabaseManager, driveId: Uuid, uniqueId: Uuid) {
        dbm.outbox.insert(
            driveId = driveId,
            uniqueId = uniqueId,
            dependencyUniqueId = null,
            priority = 0L,
            uploadType = 0L,
            json = byteArrayOf(),
            filePaths = null,
        )
    }

    @Test
    fun backedOffRowIsNotEligibleUntilItsDeadline() = runTest {
        DatabaseManager({ createInMemoryDatabase() }).use { dbm ->
            val driveId = Uuid.random()
            val uniqueId = Uuid.random()
            insertRow(dbm, driveId, uniqueId)

            val row = dbm.outbox.checkout()
            assertNotNull(row)
            // Check in as failed with a deadline 30s out — a ms epoch.
            val changed = dbm.outbox.checkInFailed(row.checkOutStamp!!, UnixTimeUtc.now().addSeconds(30).milliseconds)

            assertEquals(
                1L, changed,
                "checkInFailed must match the checked-out row — 0 means the " +
                    "swapped-positional-arguments bug is back (zombie rows)",
            )
            assertNull(
                dbm.outbox.checkout(),
                "a row backed off into the future must not be eligible for checkout",
            )
        }
    }

    @Test
    fun setNextRunTimeMakesABackedOffRowImmediatelyEligible() = runTest {
        DatabaseManager({ createInMemoryDatabase() }).use { dbm ->
            val driveId = Uuid.random()
            val uniqueId = Uuid.random()
            insertRow(dbm, driveId, uniqueId)
            val row = dbm.outbox.checkout()!!
            dbm.outbox.checkInFailed(row.checkOutStamp!!, UnixTimeUtc.now().addSeconds(3600).milliseconds)
            assertNull(dbm.outbox.checkout(), "precondition: row is backed off")

            val changed = dbm.outbox.setNextRunTime(driveId, uniqueId, 0L)

            assertEquals(1L, changed, "exactly the one queued row must be updated")
            val checkedOut = dbm.outbox.checkout()
            assertNotNull(checkedOut, "the reset row must be eligible immediately")
            assertEquals(uniqueId, checkedOut.uniqueId)
        }
    }

    @Test
    fun setNextRunTimeDoesNotTouchACheckedOutRow() = runTest {
        DatabaseManager({ createInMemoryDatabase() }).use { dbm ->
            val driveId = Uuid.random()
            val uniqueId = Uuid.random()
            insertRow(dbm, driveId, uniqueId)
            val row = dbm.outbox.checkout()
            assertNotNull(row, "precondition: row is in flight")

            val changed = dbm.outbox.setNextRunTime(driveId, uniqueId, 0L)

            assertEquals(0L, changed, "an in-flight row's nextRunTime is owned by checkInFailed")
        }
    }

    @Test
    fun setNextRunTimeOnAMissingRowChangesNothing() = runTest {
        DatabaseManager({ createInMemoryDatabase() }).use { dbm ->
            assertEquals(0L, dbm.outbox.setNextRunTime(Uuid.random(), Uuid.random(), 0L))
        }
    }

    /**
     * Unit regression THROUGH the send loop: after one failed attempt,
     * OutboxSync's checkInFailed call site must have stored a future
     * MILLISECONDS deadline. Pre-fix it stored `.seconds` (~1.7e9), which is
     * far in the past as an ms epoch — this assertion fails on that code.
     * Real-dispatcher DB (not runOutboxTest's virtual-time binding) for the
     * same reason as `OutboxSyncTest.testFailureAndRetry`.
     */
    @Test
    fun failedAttemptStoresAFutureMillisecondsDeadline() {
        val db = DatabaseManager({ createInMemoryDatabase() })
        runTest {
            val eventBus = EventBus()
            val uploader = TestUploader().apply { shouldFail = true }
            val sync = OutboxSync(
                databaseManager = db, uploader = uploader, eventBus = eventBus, scope = backgroundScope,
            )
            sync.setOnline(true)

            // checkInFailed runs BEFORE ItemFailed is emitted, so awaiting the
            // event guarantees the deadline is in the DB.
            val failedDeferred = async {
                eventBus.events.filterIsInstance<BackendEvent.OutboxEvent.ItemFailed>().first()
            }
            testScheduler.runCurrent()

            val driveId = Uuid.random()
            val uniqueId = Uuid.random()
            insertRow(db, driveId, uniqueId)

            sync.send()
            advanceUntilIdle()
            failedDeferred.await()

            val row = db.outbox.selectByDriveAndUnique(driveId, uniqueId)
            assertNotNull(row, "one failed attempt must re-queue, not drop")
            assertTrue(
                row.nextRunTime > UnixTimeUtc.now().milliseconds,
                "backoff deadline must be a FUTURE ms epoch (got ${row.nextRunTime}) — " +
                    "a seconds-epoch value here means the checkInFailed unit bug is back",
            )
            sync.clearCheckout(timeoutMs = 5_000)
        }
        db.close()
    }
}
