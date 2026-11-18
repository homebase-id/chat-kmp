package id.homebase.photobase.backup

import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.video.VideoProcessingPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class BackupWorkTrackingTest {
    private val eventBus = EventBus()
    private val drive = Uuid.random()
    private val video = Uuid.random()

    private fun BackupOutboxResults.work() = working.value[video]

    @Test
    fun aVideoShowsHowFarItsTranscodeAndUploadAreThenLeaves() = runTest {
        val results = BackupOutboxResults(BackupLedger(testDatabase().deviceBackup) { 0L }, eventBus, drive, backgroundScope)
        results.start()
        runCurrent()

        results.onPreparing(video, isVideo = true)
        assertNull(results.work(), "waiting its turn behind another video")

        eventBus.emit(BackendEvent.PayloadBundlingEvent.Video.PhaseProgress(video, "v", VideoProcessingPhase.COMPRESSING, 0.5f))
        runCurrent()
        assertEquals(45, results.work()?.percent)

        results.onHandedOff(video)
        results.onPrepareEnded(video)
        assertEquals(BackupWork(isVideo = true, stage = BackupWorkStage.Uploading), results.work())

        // A transcode tick arriving late doesn't pull it back to preparing.
        eventBus.emit(BackendEvent.PayloadBundlingEvent.Video.PhaseProgress(video, "v", VideoProcessingPhase.COMPRESSING, 0.9f))
        eventBus.emit(BackendEvent.OutboxEvent.ItemProgress(drive, video, 25f))
        runCurrent()
        assertEquals(BackupWork(isVideo = true, stage = BackupWorkStage.Uploading, percent = 25), results.work())

        eventBus.emit(BackendEvent.OutboxEvent.ItemCompleted(drive, video))
        runCurrent()
        assertNull(results.work())
    }

    @Test
    fun aPrepareThatEndsWithoutAHandOffLeaves() = runTest {
        val results = BackupOutboxResults(BackupLedger(testDatabase().deviceBackup) { 0L }, eventBus, drive, backgroundScope)
        results.onPreparing(video, isVideo = false)
        results.onPrepareEnded(video)
        assertNull(results.work())
    }

    @Test
    fun anotherDrivesUploadOrAnEditIsNotThisRowsProgress() = runTest {
        val results = BackupOutboxResults(BackupLedger(testDatabase().deviceBackup) { 0L }, eventBus, drive, backgroundScope)
        results.start()
        runCurrent()
        results.onPreparing(video, isVideo = true)
        results.onHandedOff(video)
        eventBus.emit(BackendEvent.OutboxEvent.ItemProgress(Uuid.random(), video, 50f))
        eventBus.emit(BackendEvent.OutboxEvent.ItemProgress(drive, video, 50f, isCreate = false))
        runCurrent()
        assertNull(results.work()?.percent)
    }

    @Test
    fun anOutboxRetryShowsAgainFromItsOwnStart() = runTest {
        val results = BackupOutboxResults(BackupLedger(testDatabase().deviceBackup) { 0L }, eventBus, drive, backgroundScope)
        results.start()
        runCurrent()
        results.onPreparing(video, isVideo = true)
        results.onHandedOff(video)
        eventBus.emit(BackendEvent.OutboxEvent.ItemProgress(drive, video, 60f))
        eventBus.emit(BackendEvent.OutboxEvent.ItemFailed(drive, video))
        runCurrent()
        assertNull(results.work())

        eventBus.emit(BackendEvent.OutboxEvent.ItemProgress(drive, video, 5f))
        runCurrent()
        assertEquals(BackupWork(isVideo = true, stage = BackupWorkStage.Uploading, percent = 5), results.work())
    }

    @Test
    fun progressNeverGoesBackwardsAndRoundsToTheNearestPercent() = runTest {
        val results = BackupOutboxResults(BackupLedger(testDatabase().deviceBackup) { 0L }, eventBus, drive, backgroundScope)
        results.start()
        runCurrent()
        results.onPreparing(video, isVideo = true)
        results.onHandedOff(video)
        eventBus.emit(BackendEvent.OutboxEvent.ItemProgress(drive, video, 53f))
        eventBus.emit(BackendEvent.OutboxEvent.ItemProgress(drive, video, 52f))
        runCurrent()
        assertEquals(53, results.work()?.percent)
    }

    @Test
    fun aHandOffAfterAResetLeavesNothingBehind() = runTest {
        val results = BackupOutboxResults(BackupLedger(testDatabase().deviceBackup) { 0L }, eventBus, drive, backgroundScope)
        results.onPreparing(video, isVideo = true)
        results.reset()
        results.onHandedOff(video)
        assertNull(results.work())
    }

    @Test
    fun theTilesSplitWhatHasStartedFromWhatIsWaiting() {
        val status = BackupStatus(
            pendingCount = 5,
            queuedCount = 2,
            uploadingCount = 1,
            working = listOf(BackupWork(isVideo = true, stage = BackupWorkStage.Preparing)),
        )
        assertEquals(2, status.waitingCount)
        assertEquals(4, status.inProgressCount)
    }

    @Test
    fun handedOffWorkIsWorkingUnlessSomethingPausedIt() {
        val uploading = BackupStatus(pendingCount = 1, queuedCount = 1, working = listOf(BackupWork(false, BackupWorkStage.Uploading)))
        assertEquals(BackupIndicator.Working, backupIndicatorOf(uploading, backupEnabled = true))
        assertEquals(
            BackupIndicator.Paused,
            backupIndicatorOf(uploading.copy(pauseReason = BackupPauseReason.NoNetwork), backupEnabled = true),
        )
    }
}
