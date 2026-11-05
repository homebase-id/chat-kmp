package id.homebase.photobase.backup

import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.notifications.ScheduledPushNotificationProvider
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.photobase.PhotobaseConfigFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class PhotoBackupControllerTest {
    private val db = testDatabase()
    private val source = FakeSource()
    private val triggers = RecordingTriggers()

    @AfterTest
    fun close() = db.close()

    private class RecordingTriggers : BackupTriggers {
        val armed = mutableListOf<BackupSettings>()
        val wakes = mutableListOf<Duration>()
        var takesUserRuns = true
        var userRuns = 0
        var rescans = false

        override val rescansAfterBackgroundWake: Boolean get() = rescans

        override fun rearm(settings: BackupSettings) {
            armed += settings
        }

        override suspend fun onWake(remaining: Duration, run: suspend (BackupRunRequest) -> Unit) {
            wakes += remaining
        }

        override fun startUserRequestedRun(settings: BackupSettings, progress: Flow<Float>, runInApp: suspend () -> Unit): Boolean {
            userRuns++
            return takesUserRuns
        }

        override fun batteryGuidance(): BatteryGuidance? = null
    }

    private object Offline : OutboxUploader {
        override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) = error("offline")
    }

    private val configUploads = mutableListOf<UploadFileRequest>()
    private var configSaveFailure: Exception? = null
    private var configReads = 0
    private lateinit var settingsStore: BackupSettingsStore

    private fun TestScope.controller(scope: CoroutineScope = backgroundScope): PhotoBackupController {
        val eventBus = EventBus()
        val ledger = BackupLedger(db.deviceBackup) { 1_000 }
        val store = BackupSettingsStore(db)
        val results = BackupOutboxResults(ledger, eventBus, PhotobaseProtocol.DriveAlias, backgroundScope)
        val uploader = FakeUploader(eventBus, backgroundScope)
        val run = PhotoBackupRun(
            settings = { store.settings.value },
            ledger = ledger,
            source = source,
            hasher = AssetHasher(HashedExports(source, NoFileOps), NoFileOps),
            dedupe = { emptySet() },
            uploader = uploader,
            results = results,
            probe = FakeProbe(),
            auth = { true },
            scheduler = triggers,
            isStorageFull = { false },
        )
        val push = BackupPushSchedule(
            ScheduledPushNotificationProvider(HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }), CredentialsManager()),
            OutboxSync(db, Offline, eventBus, backgroundScope).also { it.setOnline(false) },
        ) { 1_000 }
        val configFile = PhotobaseConfigFile(
            getFileHeaderByUid = { _, _ ->
                configReads++
                null
            },
            uploadFile = { request ->
                configSaveFailure?.let { throw it }
                configUploads += request
            },
            updateFileByUniqueId = { error("nothing to update") },
        )
        settingsStore = store
        return PhotoBackupController(run, source, store, push, configFile, results, triggers, scope)
    }

    private suspend fun pushRow() = db.outbox.selectByUniqueId(PhotobaseProtocol.BackupPushTagId)

    private suspend fun TestScope.enable(controller: PhotoBackupController) {
        controller.update { it.copy(enabled = true) }
        runCurrent()
    }

    @Test
    fun aWakeIsPassedOnOnlyWhenBackupIsOnAndThereIsTime() = runTest {
        val controller = controller()

        controller.onWake(20.seconds)
        assertTrue(triggers.wakes.isEmpty(), "backup is off")

        enable(controller)
        controller.onWake(1.seconds)
        assertTrue(triggers.wakes.isEmpty(), "too little of the wake is left")

        controller.onWake(20.seconds)
        assertEquals(listOf(20.seconds), triggers.wakes)
    }

    @Test
    fun turningBackupOnSchedulesThePushAndOffCancelsIt() = runTest {
        val controller = controller()

        enable(controller)
        assertNotNull(pushRow())
        assertEquals(id.homebase.api.client.notifications.ScheduledPushOutboxUploader.SchedulePush, pushRow()!!.uploadType)

        controller.update { it.copy(enabled = false) }
        runCurrent()
        assertEquals(id.homebase.api.client.notifications.ScheduledPushOutboxUploader.CancelPush, pushRow()!!.uploadType)
        assertFalse(triggers.armed.last().enabled)
    }

    private suspend fun TestScope.scheduleChecksDuring(controller: PhotoBackupController, trigger: BackupTrigger): Int {
        configReads = 0
        controller.runFromBackground(BackupRunRequest(trigger, BackupBudget(null), findDeletions = false))
        runCurrent()
        return configReads
    }

    @Test
    fun aBackgroundRunChecksTheScheduleAnotherPhoneMayHaveCancelled() = runTest {
        val controller = controller()
        enable(controller)

        assertEquals(1, scheduleChecksDuring(controller, BackupTrigger.Periodic))
        assertEquals(1, scheduleChecksDuring(controller, BackupTrigger.BackgroundRefresh))
    }

    @Test
    fun aPushOrNewPhotoDoesNotCheckTheSchedule() = runTest {
        val controller = controller()
        enable(controller)

        assertEquals(0, scheduleChecksDuring(controller, BackupTrigger.Push))
        assertEquals(0, scheduleChecksDuring(controller, BackupTrigger.MediaChanged))
    }

    @Test
    fun aBackgroundRunLeavesTheScheduleAloneWhenBackupIsOff() = runTest {
        val controller = controller()

        assertEquals(0, scheduleChecksDuring(controller, BackupTrigger.Periodic))
    }

    @Test
    fun aChangedIntervalIsSavedForEveryDeviceWithoutCreatingASchedule() = runTest {
        val controller = controller()

        controller.setPushInterval(2.hours.inWholeMilliseconds)

        assertEquals(PhotobaseProtocol.ConfigUniqueId, configUploads.single().metadata.appData.uniqueId)
        assertEquals(2.hours.inWholeMilliseconds, settingsStore.settings.value.pushIntervalMs)
        assertNull(pushRow(), "backup is off here, and no phone's job exists to correct")
    }

    @Test
    fun aFailedSaveKeepsTheOldInterval() = runTest {
        // Supervised like the real scope: backgroundScope would fail the test on the child's exception.
        val controller = controller(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        val before = settingsStore.settings.value.pushIntervalMs
        configSaveFailure = IllegalStateException("offline")

        assertFailsWith<IllegalStateException> { controller.setPushInterval(2.hours.inWholeMilliseconds) }

        assertEquals(before, settingsStore.settings.value.pushIntervalMs)
    }

    @Test
    fun aDisabledBackupDoesNotScheduleAnything() = runTest {
        val controller = controller()

        controller.update { it.copy(wifiOnly = false) }
        runCurrent()

        assertNull(pushRow())
    }

    private suspend fun TestScope.checkpointSeenByForegroundRunAfterAWake(): Map<String, String> {
        val controller = controller()
        enable(controller)
        controller.runFromBackground(BackupRunRequest(BackupTrigger.Periodic, BackupBudget(null), findDeletions = false))
        controller.onAppForeground()
        advanceTimeBy(1.seconds)
        return source.checkpointsSeen.last()
    }

    @Test
    fun theOpenAppKeepsItsCheckpointAfterAWakeByDefault() = runTest {
        assertTrue(checkpointSeenByForegroundRunAfterAWake().isNotEmpty())
    }

    @Test
    fun aPlatformThatAsksRescansTheLibraryAfterAWake() = runTest {
        triggers.rescans = true
        assertTrue(checkpointSeenByForegroundRunAfterAWake().isEmpty())
    }

    @Test
    fun theOpenAppRunsWhatThePlatformWontTakeOver() = runTest {
        val controller = controller()
        enable(controller)
        advanceUntilIdle()
        val before = source.checkpointsSeen.size

        controller.backUpNow()
        runCurrent()
        assertEquals(before, source.checkpointsSeen.size, "the platform took this one")

        triggers.takesUserRuns = false
        controller.backUpNow()
        runCurrent()
        assertEquals(before + 1, source.checkpointsSeen.size)
    }

    @Test
    fun logoutDisarmsEveryTrigger() = runTest {
        val controller = controller()
        enable(controller)

        controller.onBeforeLogout()

        assertFalse(triggers.armed.last().enabled)
    }

    @Test
    fun aProcessStartReArms() = runTest {
        val controller = controller()
        settingsStore.update { it.copy(enabled = true) }

        controller.onProcessStart()

        assertEquals(1, triggers.armed.size)
    }

    @Test
    fun aProcessStartLeavesTriggersAloneWhenBackupIsOff() = runTest {
        controller().onProcessStart()

        assertTrue(triggers.armed.isEmpty())
    }
}

private object NoFileOps : id.homebase.api.file.FileOperationsProvider {
    override fun openFileInput(path: String) = error("not used")
    override suspend fun readFileBytes(path: String): ByteArray = error("not used")
    override fun deleteTempFile(path: String) = true
    override fun getCacheDirectory() = error("not used")
    override fun getFileSize(path: String) = error("not used")
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = error("not used")
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String = error("not used")
    override suspend fun writeStream(path: String, data: kotlinx.coroutines.flow.Flow<ByteArray>) = error("not used")
}
