package id.homebase.photobase.backup

import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class BackupLedgerTest {
    private val db: DatabaseManager = testDatabase()
    private var now = 1_000_000L
    private val ledger = BackupLedger(db.deviceBackup) { now }

    @AfterTest
    fun close() = db.close()

    private suspend fun stateOf(id: String) = ledger.row(id)?.state

    @Test
    fun reapplyingTheSameListingChangesNothing() = runTest {
        ledger.apply(fullListing(asset("a")), null)
        ledger.markHashed("a", "ff", Uuid.random())
        ledger.markDone("a")

        ledger.apply(fullListing(asset("a")), null)

        assertEquals(BackupState.Done.code, stateOf("a"))
    }

    @Test
    fun anEditedAssetIsHashedAgain() = runTest {
        ledger.apply(fullListing(asset("a")), null)
        ledger.markHashed("a", "ff", Uuid.random())
        ledger.markDone("a")

        ledger.apply(DeviceChanges(emptyList(), listOf(asset("a", modifiedAtMs = 2000)), emptyList(), emptyMap(), false, null), null)

        val row = ledger.row("a")!!
        assertEquals(BackupState.New.code, row.state)
        assertEquals(null, row.uniqueId)
        assertEquals(null, row.contentHash)
    }

    @Test
    fun anAssetTheOsSaysIsGoneIsDeletedOnDevice() = runTest {
        ledger.apply(fullListing(asset("a")), null)

        ledger.apply(DeviceChanges(emptyList(), emptyList(), listOf("a"), emptyMap(), false, null), null)

        assertEquals(BackupState.DeletedOnDevice.code, stateOf("a"))
    }

    @Test
    fun deletionsAreFoundByDiffingAgainstTheCurrentIds() = runTest {
        ledger.apply(fullListing(asset("a"), asset("b")), null)

        ledger.apply(DeviceChanges(emptyList(), emptyList(), emptyList(), emptyMap(), false, setOf("b")), null)

        assertEquals(BackupState.DeletedOnDevice.code, stateOf("a"))
        assertEquals(BackupState.New.code, stateOf("b"))
    }

    @Test
    fun aDeltaWithoutCurrentIdsDeletesNothing() = runTest {
        ledger.apply(fullListing(asset("a")), null)

        ledger.apply(DeviceChanges(emptyList(), emptyList(), emptyList(), emptyMap(), false, null), null)

        assertEquals(BackupState.New.code, stateOf("a"))
    }

    @Test
    fun anAssetBackOnTheDeviceIsBackedUpAgain() = runTest {
        ledger.apply(fullListing(asset("a")), null)
        ledger.apply(DeviceChanges(emptyList(), emptyList(), listOf("a"), emptyMap(), false, null), null)

        ledger.apply(fullListing(asset("a")), null)

        assertEquals(BackupState.New.code, stateOf("a"))
    }

    @Test
    fun onlyNewFromNowSkipsOlderAssets() = runTest {
        ledger.apply(fullListing(asset("old", createdAtMs = 500), asset("new", createdAtMs = 1500)), onlyNewFromMs = 1000)

        assertEquals(BackupState.Skipped.code, stateOf("old"))
        assertEquals(BackupState.New.code, stateOf("new"))
    }

    @Test
    fun aLargeListingTakesTheSamePath() = runTest {
        val assets = List(600) { asset("a$it") }.toTypedArray()
        ledger.apply(fullListing(*assets), null)
        ledger.markHashed("a0", "ff", Uuid.random())
        ledger.markDone("a0")

        ledger.apply(fullListing(*assets), null)

        assertEquals(BackupState.Done.code, stateOf("a0"))
        assertEquals(599, ledger.counts().pending)
    }

    @Test
    fun aDropGoesBackToHashedWithABackoff() = runTest {
        val id = Uuid.random()
        ledger.apply(fullListing(asset("a")), null)
        ledger.markHashed("a", "ff", id)
        ledger.markQueued("a")

        ledger.onOutboxDropped(id, "retries exhausted")

        val row = ledger.row("a")!!
        assertEquals(BackupState.Hashed.code, row.state)
        assertEquals("retries exhausted", row.lastError)
        assertEquals(now + 1.minutes.inWholeMilliseconds, row.nextAttemptAt)
    }

    @Test
    fun theSamePhotoTwiceOnTheDeviceSettlesBothRows() = runTest {
        val id = Uuid.random()
        ledger.apply(fullListing(asset("a"), asset("b")), null)
        ledger.markHashed("a", "ff", id)
        ledger.markHashed("b", "ff", id)
        ledger.markQueued("a")
        ledger.markQueued("b")

        ledger.onOutboxCompleted(id)

        assertEquals(BackupState.Done.code, stateOf("a"))
        assertEquals(BackupState.Done.code, stateOf("b"))
    }

    @Test
    fun repeatedFailuresBackOffUpToSixHoursThenStopUntilRetried() = runTest {
        assertEquals(1.minutes, BackupLedger.backoff(1))
        assertEquals(2.minutes, BackupLedger.backoff(2))
        assertEquals(6.hours, BackupLedger.backoff(100))

        ledger.apply(fullListing(asset("a")), null)
        repeat(BackupLedger.MAX_ATTEMPTS) { ledger.recordFailure(ledger.row("a")!!, "nope") }

        assertEquals(BackupState.Failed.code, stateOf("a"))
        assertEquals(listOf(BackupFailure("a", "nope")), ledger.failures(10))

        ledger.requeueFailed()
        assertEquals(BackupState.New.code, stateOf("a"))
        assertEquals(0, ledger.row("a")!!.attempts)
    }

    @Test
    fun aNewAlbumScopeThrowsTheCheckpointAway() = runTest {
        ledger.saveDeviceCheckpoint(mapOf("volume" to "1"), emptySet())

        assertEquals(mapOf("volume" to "1"), ledger.deviceCheckpoint(emptySet(), forceFullScan = false))
        assertTrue(ledger.deviceCheckpoint(setOf("album"), forceFullScan = false).isEmpty())
        assertTrue(ledger.deviceCheckpoint(emptySet(), forceFullScan = true).isEmpty())
    }

    @Test
    fun ownMarkersStayOutOfTheDeviceCheckpoint() = runTest {
        ledger.saveDeviceCheckpoint(mapOf("volume" to "1"), emptySet())
        ledger.markInitialBackupDone()
        ledger.markSucceeded()

        assertEquals(mapOf("volume" to "1"), ledger.deviceCheckpoint(emptySet(), forceFullScan = false))
        assertTrue(ledger.isInitialBackupDone())
        assertEquals(now, ledger.lastSuccessMs())
    }
}
