package id.homebase.photobase.backup

import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.crypto.Blake3Hasher
import id.homebase.api.crypto.PhotoUniqueId
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.photobase.PhotobaseProtocol
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class PhotoBackupRunTest {
    private val db: DatabaseManager = testDatabase()
    private val eventBus = EventBus()
    private var now = 1_000_000L
    private val ledger = BackupLedger(db.deviceBackup) { now }
    private val source = FakeSource()
    private val probe = FakeProbe()
    private var settings = BackupSettings(enabled = true)
    private var hashTime: Duration = Duration.ZERO
    private val hashGates = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val existing = mutableSetOf<Uuid>()
    private val dedupeCalls = mutableListOf<List<Uuid>>()
    private var dedupeFailure: Exception? = null
    private var storageFull = false

    @AfterTest
    fun close() = db.close()

    /** Each asset's bytes are its id, so hashes are real and stable. */
    private inner class Bytes : FileOperationsProvider by NoFiles {
        override fun readFileAsFlow(path: String, chunkSize: Int): Flow<ByteArray> = flow {
            delay(hashTime)
            hashGates[path]?.await()
            emit(path.encodeToByteArray())
        }
    }

    private fun idOf(assetId: String): Uuid {
        val hasher = Blake3Hasher()
        hasher.update(assetId.encodeToByteArray())
        return PhotoUniqueId.fromContentDigests(listOf(hasher.digest()))
    }

    private fun TestScope.newRun(uploader: FakeUploader): Pair<PhotoBackupRun, BackupOutboxResults> {
        val results = BackupOutboxResults(ledger, eventBus, PhotobaseProtocol.DriveAlias, backgroundScope)
        val run = PhotoBackupRun(
            settings = { settings },
            ledger = ledger,
            source = source,
            hasher = AssetHasher(source, Bytes()),
            dedupe = { ids ->
                dedupeCalls += ids
                dedupeFailure?.let { throw it }
                ids.filter { it in existing }.toSet()
            },
            uploaderFor = { uploader },
            results = results,
            probe = probe,
            auth = { true },
            scheduler = { },
            isStorageFull = { storageFull },
        )
        return run to results
    }

    private fun TestScope.request(limit: Duration? = null, trigger: BackupTrigger = BackupTrigger.Periodic, findDeletions: Boolean = false) =
        BackupRunRequest(trigger, BackupBudget(limit, testTimeSource), findDeletions)

    private fun TestScope.uploader(sendTime: Duration = 1.seconds) = FakeUploader(eventBus, backgroundScope, sendTime)

    private suspend fun state(id: String) = ledger.row(id)?.state

    @Test
    fun everythingEndsUpBackedUp() = runTest {
        source.next = fullListing(asset("a"), asset("b"), asset("c"))
        val uploader = uploader()
        val (run, _) = newRun(uploader)

        val outcome = run.run(request())
        advanceUntilIdle()

        assertEquals(BackupRunOutcome.Finished, outcome)
        assertEquals(setOf("a", "b", "c"), uploader.handedOff.toSet())
        assertEquals(3, uploader.handedOff.size)
        assertEquals(3, ledger.counts().done)
        assertEquals(0, ledger.counts().pending)
        assertTrue(ledger.isInitialBackupDone())
    }

    @Test
    fun aFinishedHashReachesUploadWhileAnotherIsStillHashing() = runTest {
        // The slow hash ends only once the fast photo reaches upload, so waiting for hashing can't pass.
        val slowHash = CompletableDeferred<Unit>()
        hashGates["slow"] = slowHash
        source.next = fullListing(asset("fast"), asset("slow"))
        val uploader = uploader()
        uploader.result = {
            slowHash.complete(Unit)
            HandOffResult.Queued
        }
        val (run, _) = newRun(uploader)

        run.run(request())

        assertEquals(setOf("fast", "slow"), uploader.handedOff.toSet())
    }

    @Test
    fun theCheckpointIsSavedOnlyWithTheChangesItCovers() = runTest {
        source.next = fullListing(asset("a"), checkpoint = mapOf("volume" to "7"))
        source.failure = IllegalStateException("library unavailable")
        val (run, _) = newRun(uploader())

        val failed = runCatching { run.run(request()) }
        assertTrue(failed.isFailure)
        assertTrue(ledger.deviceCheckpoint(emptySet(), forceFullScan = false).isEmpty())
        assertEquals(null, ledger.row("a"))

        source.failure = null
        run.run(request())
        advanceUntilIdle()

        assertEquals(mapOf("volume" to "7"), ledger.deviceCheckpoint(emptySet(), forceFullScan = false))
        run.run(request())
        assertEquals(mapOf("volume" to "7"), source.checkpointsSeen.last())
    }

    @Test
    fun whatIsAlreadyOnTheDriveIsNeverUploaded() = runTest {
        existing += idOf("a")
        source.next = fullListing(asset("a"), asset("b"))
        val uploader = uploader()
        val (run, _) = newRun(uploader)

        run.run(request())
        advanceUntilIdle()

        assertEquals(listOf("b"), uploader.handedOff)
        assertEquals(BackupState.Done.code, state("a"))
        assertEquals(BackupState.Done.code, state("b"))
        assertEquals(setOf(idOf("a"), idOf("b")), dedupeCalls.flatten().toSet())
    }

    @Test
    fun aFailedDedupeUploadsNothingAndRetriesLater() = runTest {
        dedupeFailure = IllegalStateException("server down")
        source.next = fullListing(asset("a"))
        val uploader = uploader()
        val (run, _) = newRun(uploader)

        run.run(request())

        assertTrue(uploader.handedOff.isEmpty())
        val row = ledger.row("a")!!
        assertEquals(BackupState.Hashed.code, row.state)
        assertEquals(1, row.attempts)
        assertTrue(row.nextAttemptAt > now)
    }

    @Test
    fun bytesChangedBeforeEncryptingGoBackToNew() = runTest {
        source.next = fullListing(asset("a"))
        val uploader = uploader()
        uploader.result = { HandOffResult.ChangedUnderUs }
        val (run, _) = newRun(uploader)

        run.run(request())

        val row = ledger.row("a")!!
        assertEquals(BackupState.New.code, row.state)
        assertEquals("changed while backing up", row.lastError)
    }

    @Test
    fun anOutboxDropGoesBackToHashedSoThePhotoIsNotLost() = runTest {
        source.next = fullListing(asset("a"))
        val uploader = uploader()
        uploader.sendsComplete = false
        val (run, results) = newRun(uploader)
        backgroundScope.launch {
            results.unsettled.collect { pending ->
                pending.forEach { eventBus.emit(BackendEvent.OutboxEvent.OutboxItemDropped(PhotobaseProtocol.DriveAlias, it, 1, "retries exhausted")) }
            }
        }

        run.run(request())
        advanceUntilIdle()

        val row = ledger.row("a")!!
        assertEquals(BackupState.Hashed.code, row.state)
        assertEquals("retries exhausted", row.lastError)
        assertTrue(row.nextAttemptAt > now)
        assertEquals(0, ledger.counts().done)
        assertFalse(ledger.isInitialBackupDone())
    }

    @Test
    fun aPermanentlyUnavailableAssetIsSkippedWithItsReason() = runTest {
        source.next = fullListing(asset("a"), asset("b"))
        val uploader = uploader()
        uploader.result = {
            if (it.deviceAssetId == "a") throw AssetPermanentlyUnavailableException("asset gone") else HandOffResult.Queued
        }
        val (run, _) = newRun(uploader)

        run.run(request())
        advanceUntilIdle()

        assertEquals(BackupState.Skipped.code, state("a"))
        assertEquals("asset gone", ledger.row("a")!!.lastError)
        assertEquals(BackupState.Done.code, state("b"))
    }

    @Test
    fun aBudgetStopsNewWorkAndLeavesTheRestForLater() = runTest {
        hashTime = 10.seconds
        source.next = fullListing(*List(20) { asset("a$it") }.toTypedArray())
        val (run, _) = newRun(uploader())

        val outcome = run.run(request(limit = 25.seconds))

        assertEquals(BackupRunOutcome.OutOfTime, outcome)
        val counts = ledger.counts()
        assertTrue(counts.pending > 0, "some assets must be left: $counts")
        assertFalse(ledger.isInitialBackupDone())
    }

    @Test
    fun aKilledRunResumesWithoutUploadingAnythingTwice() = runTest {
        hashTime = 1.seconds
        source.next = fullListing(*List(10) { asset("a$it", createdAtMs = it.toLong()) }.toTypedArray())
        val uploader = uploader(sendTime = 0.seconds)
        val (run, _) = newRun(uploader)

        val killed = launch { run.run(request()) }
        delay(2500)
        killed.cancel(CancellationException("process killed"))
        advanceUntilIdle()
        assertTrue(ledger.counts().pending > 0)

        run.run(request())
        advanceUntilIdle()

        assertEquals(10, ledger.counts().done)
        assertEquals(10, uploader.handedOff.toSet().size)
        assertEquals(10, uploader.handedOff.size)
    }

    @Test
    fun aSecondRunWhileOneIsGoingDoesNothing() = runTest {
        hashTime = 10.seconds
        source.next = fullListing(asset("a"))
        val (run, _) = newRun(uploader())
        val first = launch { run.run(request()) }
        delay(1)

        assertEquals(BackupRunOutcome.AlreadyRunning, run.run(request()))

        first.join()
    }

    @Test
    fun aRunStopsAndSaysWhyWhenBackupCannotGo() = runTest {
        val uploader = uploader()
        val (run, _) = newRun(uploader)

        settings = BackupSettings(enabled = false)
        assertEquals(BackupRunOutcome.Paused, run.run(request()))
        assertEquals(BackupPauseReason.BackupOff, run.status.value.pauseReason)

        settings = BackupSettings(enabled = true)
        probe.access = LibraryAccess.None
        run.run(request())
        assertEquals(BackupPauseReason.NoLibraryAccess, run.status.value.pauseReason)

        probe.access = LibraryAccess.Full
        probe.state = probe.state.copy(isUnmetered = false)
        run.run(request())
        assertEquals(BackupPauseReason.WaitingForWifi, run.status.value.pauseReason)

        probe.state = probe.state.copy(isUnmetered = true)
        storageFull = true
        run.run(request())
        assertEquals(BackupPauseReason.StorageFull, run.status.value.pauseReason)

        assertTrue(source.checkpointsSeen.isEmpty())
    }

    @Test
    fun theInitialBackupGoesOldestFirstAndLaterOnesNewestFirst() = runTest {
        source.next = fullListing(asset("old", createdAtMs = 1), asset("new", createdAtMs = 2))
        val uploader = uploader()
        val (run, _) = newRun(uploader)

        run.run(request())
        advanceUntilIdle()
        assertEquals(listOf("old", "new"), uploader.handedOff)

        uploader.handedOff.clear()
        source.next = DeviceChanges(listOf(asset("older", createdAtMs = 3), asset("newest", createdAtMs = 4)), emptyList(), emptyList(), emptyMap(), false, null)
        run.run(request())
        advanceUntilIdle()
        assertEquals(listOf("newest", "older"), uploader.handedOff)
    }

    @Test
    fun videosWaitUnlessAllowed() = runTest {
        probe.state = probe.state.copy(isCharging = false)
        source.next = fullListing(asset("photo"), asset("clip", mediaType = BackupMediaType.Video))
        val uploader = uploader()
        val (run, _) = newRun(uploader)

        run.run(request())
        advanceUntilIdle()

        assertEquals(listOf("photo"), uploader.handedOff)
        assertEquals(BackupState.New.code, state("clip"))
    }

    @Test
    fun aRowWhoseSendVanishedIsSettledByDedupe() = runTest {
        val id = idOf("a")
        ledger.apply(fullListing(asset("a")), null)
        ledger.markHashed("a", "ff", id)
        ledger.markQueued("a")
        existing += id
        source.next = fullListing(asset("a"))
        val uploader = uploader()
        val (run, _) = newRun(uploader)
        now += 2.minutes.inWholeMilliseconds

        run.run(request(trigger = BackupTrigger.Foreground))
        advanceUntilIdle()

        // The backoff of the vanished send keeps it out of this run; the next one settles it.
        now += 2.minutes.inWholeMilliseconds
        run.run(request(trigger = BackupTrigger.Foreground))
        advanceUntilIdle()

        assertEquals(BackupState.Done.code, state("a"))
        assertTrue(uploader.handedOff.isEmpty())
    }

    @Test
    fun aDeadOutboxStopsTheRunFromEncryptingTheWholeLibrary() = runTest {
        source.next = fullListing(*List(30) { asset("a$it") }.toTypedArray())
        val uploader = uploader()
        uploader.sendsComplete = false
        val (run, _) = newRun(uploader)

        val outcome = run.run(request())

        assertEquals(BackupRunOutcome.Finished, outcome)
        assertTrue(uploader.handedOff.size <= 8, "handed off ${uploader.handedOff.size}")
    }

    @Test
    fun aSendThatOutlivesTheProcessIsNotWaitedFor() = runTest {
        source.next = fullListing(*List(10) { asset("a$it") }.toTypedArray())
        val uploader = uploader()
        uploader.sendsComplete = false
        uploader.outlivesProcess = true
        uploader.maxHandedOff = 4
        uploader.result = { HandOffResult.Sending(taskId = 7) }
        val (run, results) = newRun(uploader)

        val started = testTimeSource.markNow()
        val outcome = run.run(request())

        assertEquals(BackupRunOutcome.Finished, outcome)
        assertEquals(Duration.ZERO, started.elapsedNow())
        assertTrue(results.unsettled.value.isEmpty())
        val counts = ledger.counts()
        assertTrue(counts.uploading in 4..5, "a batch, not the library: $counts")
        assertEquals(7, ledger.rowsInOutbox().first().uploadTaskId)
    }

    @Test
    fun theOutboxIsKickedForWhatWasHandedOff() = runTest {
        source.next = fullListing(asset("a"))
        val uploader = uploader()
        val (run, _) = newRun(uploader)

        run.run(request())

        assertTrue(uploader.kicks > 0)
    }

    @Test
    fun otherDrivesEventsAreIgnored() = runTest {
        val id = idOf("a")
        ledger.apply(fullListing(asset("a")), null)
        ledger.markHashed("a", "ff", id)
        ledger.markQueued("a")
        val results = BackupOutboxResults(ledger, eventBus, PhotobaseProtocol.DriveAlias, backgroundScope)
        results.start()
        advanceUntilIdle()

        eventBus.emit(BackendEvent.OutboxEvent.ItemCompleted(Uuid.random(), id))
        advanceUntilIdle()

        assertEquals(BackupState.Queued.code, state("a"))
    }
}

private object NoFiles : FileOperationsProvider {
    override fun openFileInput(path: String): InputProvider = error("not used")
    override suspend fun readFileBytes(path: String): ByteArray = error("not used")
    override fun deleteTempFile(path: String): Boolean = true
    override fun getCacheDirectory(): String = error("not used")
    override fun getFileSize(path: String): Long = error("not used")
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used")
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used")
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used")
}
