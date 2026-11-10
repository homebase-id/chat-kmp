package id.homebase.photobase.backup

import id.homebase.api.client.NetworkException
import id.homebase.api.client.ServerException
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
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
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
    private var signedIn = CompletableDeferred(true)
    private val ranWithWorkLeft = mutableListOf<Boolean>()
    private var stuckAlerts = 0
    private var continuations = 0

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
            hasher = Bytes().let { AssetHasher(HashedExports(source, it), it) },
            dedupe = { ids ->
                dedupeCalls += ids
                dedupeFailure?.let { throw it }
                ids.filter { it in existing }.toSet()
            },
            uploader = uploader,
            results = results,
            probe = probe,
            auth = { signedIn.await() },
            scheduler = object : BackupScheduler {
                override fun rearm(settings: BackupSettings) = Unit
                override fun onBackupRan(workLeft: Boolean) { ranWithWorkLeft += workLeft }
                override fun continueSoon(settings: BackupSettings) { continuations++ }
                override suspend fun alertBackupStuck() { stuckAlerts++ }
            },
            isStorageFull = { storageFull },
        )
        return run to results
    }

    private fun TestScope.request(limit: Duration? = null, trigger: BackupTrigger = BackupTrigger.Periodic) =
        BackupRunRequest(trigger, BackupBudget(limit, testTimeSource))

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
    fun photosAreHashedWhileTheLoginIsStillLoading() = runTest {
        signedIn = CompletableDeferred()
        source.next = fullListing(asset("a"))
        val uploader = uploader()
        val (run, _) = newRun(uploader)

        val going = launch { run.run(request()) }
        advanceTimeBy(1.seconds)

        assertEquals(BackupState.Hashed.code, state("a"))
        assertTrue(dedupeCalls.isEmpty())
        assertTrue(uploader.handedOff.isEmpty())

        signedIn.complete(true)
        going.join()

        assertEquals(listOf("a"), uploader.handedOff)
    }

    @Test
    fun aSignedOutRunNeverReachesTheServer() = runTest {
        signedIn = CompletableDeferred()
        source.next = fullListing(asset("a"))
        val uploader = uploader()
        val (run, _) = newRun(uploader)

        val going = launch { assertEquals(BackupRunOutcome.Paused, run.run(request())) }
        advanceTimeBy(1.seconds)
        signedIn.complete(false)
        going.join()

        assertEquals(BackupPauseReason.SignedOut, run.status.value.pauseReason)
        assertTrue(dedupeCalls.isEmpty())
        assertTrue(uploader.handedOff.isEmpty())
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
    fun refreshingTheStatusUploadsWhatTheDriveLostAndKeepsWhatItHas() = runTest {
        source.next = fullListing(asset("a"), asset("b"))
        val uploader = uploader()
        val (run, _) = newRun(uploader)
        run.run(request())
        advanceUntilIdle()
        existing += idOf("a")
        uploader.handedOff.clear()
        dedupeCalls.clear()

        run.run(request(trigger = BackupTrigger.UserRequested))
        advanceUntilIdle()
        assertTrue(dedupeCalls.isEmpty())
        assertTrue(uploader.handedOff.isEmpty())

        run.recheckDrive()
        run.run(request(trigger = BackupTrigger.UserRequested))
        advanceUntilIdle()

        assertEquals(setOf(idOf("a"), idOf("b")), dedupeCalls.flatten().toSet())
        assertEquals(listOf("b"), uploader.handedOff)
        assertEquals(BackupState.Done.code, state("a"))
        assertEquals(BackupState.Done.code, state("b"))
        assertEquals(2, ledger.counts().done)
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
    fun anOutageDuringDedupeCostsNoAttemptAndStopsChecking() = runTest {
        dedupeFailure = NetworkException(IOException("no route to host"))
        source.next = fullListing(asset("a"), asset("b"), asset("c"))
        val uploader = uploader()
        val (run, _) = newRun(uploader)

        run.run(request())

        assertTrue(uploader.handedOff.isEmpty())
        assertEquals(1, dedupeCalls.size)
        val rows = listOf("a", "b", "c").map { ledger.row(it)!! }
        assertTrue(rows.all { it.attempts == 0L })
        val checked = dedupeCalls.single().toSet()
        assertTrue(rows.filter { idOf(it.deviceAssetId) in checked }.all { it.nextAttemptAt > now })
    }

    @Test
    fun aServerErrorDuringDedupeCostsNoAttempt() = runTest {
        dedupeFailure = ServerException(503, null, null)
        source.next = fullListing(asset("a"))
        val (run, _) = newRun(uploader())

        run.run(request())

        val row = ledger.row("a")!!
        assertEquals(BackupState.Hashed.code, row.state)
        assertEquals(0, row.attempts)
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
    fun losingWifiStopsTheRunAndLeavesTheRestForLater() = runTest {
        hashGates["slow"] = CompletableDeferred()
        source.next = fullListing(asset("slow"))
        val (run, _) = newRun(uploader())

        val outcome = async { run.run(request()) }
        advanceTimeBy(1.seconds)
        assertFalse(outcome.isCompleted)
        probe.state = probe.state.copy(isUnmetered = false)
        advanceTimeBy(6.seconds)

        assertEquals(BackupRunOutcome.Paused, outcome.await())
        assertEquals(BackupPauseReason.WaitingForWifi, run.status.value.pauseReason)
        assertEquals(BackupState.New.code, state("slow"))
    }

    @Test
    fun losingWifiDoesNotStopARunThatMayUseMobileData() = runTest {
        settings = settings.copy(wifiOnly = false)
        hashGates["slow"] = CompletableDeferred()
        source.next = fullListing(asset("slow"))
        val (run, _) = newRun(uploader())

        val outcome = async { run.run(request()) }
        advanceTimeBy(1.seconds)
        probe.state = probe.state.copy(isUnmetered = false)
        advanceTimeBy(6.seconds)

        assertFalse(outcome.isCompleted)
        hashGates.getValue("slow").complete(Unit)
        assertEquals(BackupRunOutcome.Finished, outcome.await())
    }

    @Test
    fun losingTheNetworkVideosNeedStopsTheVideoAndCarriesOnWithPhotos() = runTest {
        settings = settings.copy(wifiOnly = false, videosOnCellular = false)
        hashGates["clip"] = CompletableDeferred()
        hashGates["photo"] = CompletableDeferred()
        source.next = fullListing(asset("photo"), asset("clip", mediaType = BackupMediaType.Video))
        val uploader = uploader()
        val (run, _) = newRun(uploader)

        val outcome = async { run.run(request()) }
        advanceTimeBy(1.seconds)
        probe.state = probe.state.copy(isUnmetered = false)
        advanceTimeBy(6.seconds)
        hashGates.getValue("photo").complete(Unit)

        outcome.await()
        assertEquals(listOf("photo"), uploader.handedOff)
        assertEquals(BackupState.New.code, state("clip"))
    }

    @Test
    fun aShortWindowSkipsAPhotoNotOnThePhoneWithoutCountingAFailure() = runTest {
        source.onlyInCloud += "cloud"
        source.next = fullListing(asset("cloud"), asset("local"))
        val uploader = uploader()
        val (run, _) = newRun(uploader)

        run.run(request(limit = 25.seconds, trigger = BackupTrigger.Push))

        assertEquals(listOf("local"), uploader.handedOff)
        val cloud = ledger.row("cloud")!!
        assertEquals(BackupState.New.code, cloud.state)
        assertEquals(0L, cloud.attempts)
        assertTrue(cloud.nextAttemptAt > now, "must not use up the next short window too")
    }

    @Test
    fun aVideoThatGaveWayToAChatSendIsRetriedWithoutCountingAFailure() = runTest {
        source.next = fullListing(asset("clip"))
        val uploader = uploader()
        uploader.result = { throw id.homebase.api.video.VideoWorkYieldedException() }
        val (run, _) = newRun(uploader)

        run.run(request())

        assertTrue(uploader.markedBackground, "chat sends can only go first if backup's work is marked")
        val clip = ledger.row("clip")!!
        assertEquals(0L, clip.attempts)
        assertTrue(clip.nextAttemptAt > now)
    }

    @Test
    fun aRunThatMayDownloadFetchesWhatAShortWindowSkipped() = runTest {
        source.onlyInCloud += "cloud"
        source.next = fullListing(asset("cloud"))
        val uploader = uploader()
        val (run, _) = newRun(uploader)
        run.run(request(limit = 25.seconds, trigger = BackupTrigger.Push))
        assertTrue(uploader.handedOff.isEmpty())

        run.run(request(trigger = BackupTrigger.BackgroundProcessing))

        assertEquals(listOf("cloud"), uploader.handedOff)
    }

    @Test
    fun aDayOfPausedRunsAlertsOncePerDay() = runTest {
        ledger.apply(fullListing(asset("a")), null)
        ledger.markRan()
        probe.state = probe.state.copy(isUnmetered = false)
        val (run, _) = newRun(uploader())
        val hour = 60 * 60 * 1000L

        now += 23 * hour
        run.run(request())
        assertEquals(0, stuckAlerts)

        now += 2 * hour
        run.run(request())
        run.run(request())
        assertEquals(1, stuckAlerts)

        now += 24 * hour
        run.run(request())
        assertEquals(2, stuckAlerts)
    }

    @Test
    fun aPausedRunStartsTheClockWhenBackupHasNeverRun() = runTest {
        ledger.apply(fullListing(asset("a")), null)
        probe.state = probe.state.copy(isUnmetered = false)
        val (run, _) = newRun(uploader())

        run.run(request())

        assertEquals(0, stuckAlerts)
        assertEquals(emptyList(), ranWithWorkLeft)
    }

    @Test
    fun aRunThatDidItsWorkCountsAsRan() = runTest {
        source.next = fullListing(asset("a"))
        val (run, _) = newRun(uploader())

        run.run(request())

        assertEquals(listOf(false), ranWithWorkLeft, "nothing left, so iOS removes the stuck alert")
    }

    @Test
    fun aRunThatLeavesWorkSaysSo() = runTest {
        source.next = fullListing(asset("a"))
        dedupeFailure = IllegalStateException("bad answer")
        val (run, _) = newRun(uploader())

        run.run(request())

        assertEquals(listOf(true), ranWithWorkLeft)
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
        assertEquals(1, continuations, "it backed some up, so another run carries on")
    }

    @Test
    fun aRunOutOfTimeThatMovedNothingDoesNotAskForAnother() = runTest {
        hashTime = 30.seconds
        source.next = fullListing(asset("a"), asset("b"))
        val (run, _) = newRun(uploader())

        assertEquals(BackupRunOutcome.OutOfTime, run.run(request(limit = 25.seconds)))

        assertEquals(0, continuations)
    }

    @Test
    fun aRunThatFinishesOrPausesDoesNotAskForAnother() = runTest {
        source.next = fullListing(asset("a"))
        val (run, _) = newRun(uploader())
        run.run(request())

        probe.state = probe.state.copy(isUnmetered = false)
        source.next = fullListing(asset("a"), asset("b"))
        run.run(request(limit = 25.seconds))

        assertEquals(0, continuations)
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
    fun aSecondBackgroundRunWhileOneIsGoingDoesNothing() = runTest {
        hashTime = 10.seconds
        source.next = fullListing(asset("a"))
        val (run, _) = newRun(uploader())
        val first = launch { run.run(request()) }
        delay(1)

        assertEquals(BackupRunOutcome.AlreadyRunning, run.run(request()))

        first.join()
    }

    @Test
    fun backUpNowTakesOverFromAForegroundRun() = runTest {
        source.next = fullListing(asset("a"), asset("b"), asset("c"))
        hashTime = 10.seconds
        val uploader = uploader(sendTime = 60.seconds)
        val (run, _) = newRun(uploader)
        val foreground = async { run.run(request(trigger = BackupTrigger.Foreground)) }
        advanceTimeBy(1.seconds)

        val userRequested = async { run.run(request(trigger = BackupTrigger.UserRequested)) }
        advanceUntilIdle()

        assertEquals(BackupRunOutcome.Superseded, foreground.await())
        assertEquals(BackupRunOutcome.Finished, userRequested.await())
        assertEquals(setOf("a", "b", "c"), uploader.handedOff.toSet())
        assertEquals(3, ledger.counts().done)
    }

    @Test
    fun aNewBackUpNowReplacesTheOneGoing() = runTest {
        source.next = fullListing(asset("a"))
        hashTime = 10.seconds
        val uploader = uploader()
        val (run, _) = newRun(uploader)
        val first = async { run.run(request(trigger = BackupTrigger.UserRequested)) }
        advanceTimeBy(1.seconds)

        source.next = fullListing(asset("a"), asset("b"))
        val second = async { run.run(request(trigger = BackupTrigger.UserRequested)) }
        advanceUntilIdle()

        assertEquals(BackupRunOutcome.Superseded, first.await())
        assertEquals(BackupRunOutcome.Finished, second.await())
        assertEquals(setOf("a", "b"), uploader.handedOff.toSet())
    }

    @Test
    fun openingTheAppDuringABackgroundRunCarriesOnAfterIt() = runTest {
        source.next = fullListing(*List(20) { asset("a$it") }.toTypedArray())
        hashTime = 10.seconds
        val uploader = uploader()
        val (run, _) = newRun(uploader)
        val background = async { run.run(request(limit = 25.seconds)) }
        advanceTimeBy(1.seconds)

        val foreground = async { run.run(request(trigger = BackupTrigger.Foreground)) }
        advanceUntilIdle()

        assertEquals(BackupRunOutcome.OutOfTime, background.await())
        assertEquals(BackupRunOutcome.Finished, foreground.await())
        assertEquals(20, ledger.counts().done)
    }

    @Test
    fun aForegroundRunWaitingBehindBackUpNowLeavesItToBackUpNow() = runTest {
        source.next = fullListing(asset("a"), asset("b"))
        hashTime = 10.seconds
        val uploader = uploader()
        val (run, _) = newRun(uploader)
        val background = async { run.run(request()) }
        advanceTimeBy(1.seconds)
        val foreground = async { run.run(request(trigger = BackupTrigger.Foreground)) }
        advanceTimeBy(1.seconds)

        val userRequested = async { run.run(request(trigger = BackupTrigger.UserRequested)) }
        advanceUntilIdle()

        assertEquals(BackupRunOutcome.Superseded, background.await())
        assertEquals(BackupRunOutcome.Superseded, foreground.await())
        assertEquals(BackupRunOutcome.Finished, userRequested.await())
        assertEquals(2, ledger.counts().done)
    }

    @Test
    fun aSupersededRunsCallerBeingCancelledStillCancels() = runTest {
        source.next = fullListing(asset("a"))
        hashTime = 10.seconds
        val (run, _) = newRun(uploader())
        val foreground = launch { run.run(request(trigger = BackupTrigger.Foreground)) }
        advanceTimeBy(1.seconds)

        foreground.cancel()
        val userRequested = async { run.run(request(trigger = BackupTrigger.UserRequested)) }
        advanceUntilIdle()

        assertTrue(foreground.isCancelled)
        assertEquals(BackupRunOutcome.Finished, userRequested.await())
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
    fun aPhoneThatFillsUpMidRunPausesAndTheFailedWriteCostsNoAttempt() = runTest {
        val gate = CompletableDeferred<Unit>()
        hashGates["a"] = gate
        source.next = fullListing(asset("a"))
        val (run, _) = newRun(uploader())

        val outcome = async { run.run(request()) }
        runCurrent()
        probe.state = probe.state.copy(freeStorageBytes = 1024)
        gate.completeExceptionally(IllegalStateException("No space left on device"))

        assertEquals(BackupRunOutcome.Paused, outcome.await())
        assertEquals(BackupPauseReason.PhoneStorageLow, run.status.value.pauseReason)
        assertEquals(0, ledger.row("a")?.attempts)
        assertEquals(BackupState.New.code, state("a"))
    }

    @Test
    fun aHotPhoneStopsStartingWorkAndCarriesOnOnceCool() = runTest {
        source.next = fullListing(asset("a"))
        probe.state = probe.state.copy(isOverheated = true)
        val (run, _) = newRun(uploader())

        assertEquals(BackupRunOutcome.Paused, run.run(request(trigger = BackupTrigger.Foreground)))
        assertEquals(BackupPauseReason.TooHot, run.status.value.pauseReason)

        probe.state = probe.state.copy(isOverheated = false)
        assertEquals(BackupRunOutcome.Finished, run.run(request()))
        assertEquals(BackupState.Done.code, state("a"))
    }

    @Test
    fun statusSaysWhenPhotoLocationsAreWithheld() = runTest {
        val (run, _) = newRun(uploader())

        run.refreshStatus()
        assertFalse(run.status.value.photoLocationsWithheld)

        probe.locationsWithheld = true
        run.refreshStatus()
        assertTrue(run.status.value.photoLocationsWithheld)

        probe.access = LibraryAccess.None
        run.refreshStatus()
        assertFalse(run.status.value.photoLocationsWithheld)
    }

    @Test
    fun theFirstBackupGoesNewestFirstByDefault() = runTest {
        source.next = fullListing(asset("old", createdAtMs = 1), asset("new", createdAtMs = 2))
        val uploader = uploader()
        val (run, _) = newRun(uploader)

        run.run(request())
        advanceUntilIdle()

        assertEquals(listOf("new", "old"), uploader.handedOff)
        assertTrue(ledger.isInitialBackupDone())
    }

    @Test
    fun turnedOffTheFirstBackupGoesOldestFirstAndLaterOnesNewestFirst() = runTest {
        settings = settings.copy(newestFirst = false)
        source.next = fullListing(asset("old", createdAtMs = 1), asset("new", createdAtMs = 2))
        val uploader = uploader()
        val (run, _) = newRun(uploader)

        run.run(request())
        advanceUntilIdle()
        assertEquals(listOf("old", "new"), uploader.handedOff)

        uploader.handedOff.clear()
        source.next = DeviceChanges(listOf(asset("older", createdAtMs = 3), asset("newest", createdAtMs = 4)), emptyList(), emptyMap(), null)
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
        ledger.markHashed("a", "ff", id, 10)
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
    fun rowsTheOutboxIsBackingOffDoNotStopNewUploads() = runTest {
        val first = List(6) { asset("a$it") }
        source.next = fullListing(*first.toTypedArray())
        val uploader = uploader()
        uploader.sendsFail = true
        val (run, _) = newRun(uploader)
        run.run(request())
        advanceUntilIdle()
        assertEquals(6, ledger.rowsInOutbox().size)

        uploader.sendsFail = false
        uploader.stillSending = true
        source.next = fullListing(*(first + asset("b")).toTypedArray())
        run.run(request())
        advanceUntilIdle()

        assertEquals(BackupState.Done.code, state("b"))
    }

    @Test
    fun anOutboxFailingEverySendStillStopsAtTwiceTheLimit() = runTest {
        source.next = fullListing(*List(30) { asset("a$it") }.toTypedArray())
        val uploader = uploader()
        uploader.sendsFail = true
        val (run, _) = newRun(uploader)

        run.run(request())
        advanceUntilIdle()

        assertTrue(uploader.handedOff.size <= 14, "handed off ${uploader.handedOff.size}")
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
    fun aForegroundRunKeepsGoingAsSessionUploadsLand() = runTest {
        source.next = fullListing(*List(10) { asset("a$it") }.toTypedArray())
        val uploader = uploader()
        uploader.outlivesProcess = true
        uploader.maxHandedOff = 4
        uploader.result = { row ->
            backgroundScope.launch {
                delay(1.seconds)
                ledger.onOutboxCompleted(Uuid.parse(row.uniqueId!!))
            }
            HandOffResult.Sending(taskId = 7)
        }
        val (run, _) = newRun(uploader)

        run.run(request(trigger = BackupTrigger.Foreground))

        assertEquals(10, uploader.handedOff.size)
        assertEquals(10, ledger.counts().done)
        assertTrue(ledger.isInitialBackupDone())
    }

    @Test
    fun aForegroundRunStopsWhenTheSessionGoesQuiet() = runTest {
        source.next = fullListing(*List(10) { asset("a$it") }.toTypedArray())
        val uploader = uploader()
        uploader.outlivesProcess = true
        uploader.maxHandedOff = 4
        uploader.result = { HandOffResult.Sending(taskId = 7) }
        val (run, _) = newRun(uploader)

        assertEquals(BackupRunOutcome.Finished, run.run(request(trigger = BackupTrigger.Foreground)))

        assertTrue(uploader.handedOff.size in 4..5, "handed off ${uploader.handedOff.size}")
        assertFalse(ledger.isInitialBackupDone())
    }

    @Test
    fun uploadsStillWithTheSessionAreNotASuccessYet() = runTest {
        source.next = fullListing(asset("a"), asset("b"))
        val uploader = uploader()
        uploader.outlivesProcess = true
        uploader.result = { HandOffResult.Sending(taskId = 7) }
        val (run, _) = newRun(uploader)

        run.run(request())

        assertEquals(2, ledger.counts().uploading)
        assertFalse(ledger.isInitialBackupDone())
    }

    @Test
    fun aRowHandedToTheSessionSaysSoEvenIfTheRunIsCancelled() = runTest {
        source.next = fullListing(asset("a"))
        val uploader = uploader()
        uploader.outlivesProcess = true
        val (run, _) = newRun(uploader)
        val going = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { run.run(request(trigger = BackupTrigger.Foreground)) }
        uploader.result = {
            going.cancel()
            HandOffResult.Sending(taskId = 7)
        }

        going.start()
        going.join()

        assertEquals(BackupState.Uploading.code, state("a"))
    }

    @Test
    fun videosKeepTheOutboxLimitWhenTheSessionTakesMore() = runTest {
        source.next = fullListing(*List(20) { asset("v$it", mediaType = BackupMediaType.Video) }.toTypedArray())
        val uploader = uploader()
        uploader.sendsComplete = false
        uploader.outlivesProcess = true
        uploader.maxHandedOff = 50
        val (run, _) = newRun(uploader)

        run.run(request(trigger = BackupTrigger.Foreground))

        assertTrue(uploader.handedOff.size <= MAX_OUTBOX_HANDED_OFF + 2, "handed off ${uploader.handedOff.size}")
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
        ledger.markHashed("a", "ff", id, 10)
        ledger.markQueued("a")
        val results = BackupOutboxResults(ledger, eventBus, PhotobaseProtocol.DriveAlias, backgroundScope)
        results.start()
        advanceUntilIdle()

        eventBus.emit(BackendEvent.OutboxEvent.ItemCompleted(Uuid.random(), id))
        advanceUntilIdle()

        assertEquals(BackupState.Queued.code, state("a"))
    }
}

internal object NoFiles : FileOperationsProvider {
    override fun openFileInput(path: String): InputProvider = error("not used")
    override suspend fun readFileBytes(path: String): ByteArray = error("not used")
    override fun deleteTempFile(path: String): Boolean = true
    override fun getCacheDirectory(): String = error("not used")
    override fun getFileSize(path: String): Long = error("not used")
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used")
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used")
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) = error("not used")
}
