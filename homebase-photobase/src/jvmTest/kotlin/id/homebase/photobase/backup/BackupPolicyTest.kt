package id.homebase.photobase.backup

import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.notifications.CancelScheduledPushRequest
import id.homebase.api.client.notifications.SchedulePushNotificationRequest
import id.homebase.api.client.notifications.ScheduledPushNotificationEntry
import id.homebase.api.client.notifications.ScheduledPushNotificationOptions
import id.homebase.api.client.notifications.ScheduledPushNotificationProvider
import id.homebase.api.client.notifications.ScheduledPushNotificationState
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.image.ImageMetadata
import id.homebase.api.serialization.OutboxSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import id.homebase.core.config.AppConfig
import id.homebase.core.photobase.PhotobaseProtocol
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

class BackupPolicyTest {
    private val db = testDatabase()

    @AfterTest
    fun close() = db.close()

    private val online = DeviceState(hasNetwork = true, isUnmetered = true, isCharging = false, isBatteryLow = false, isLowPowerMode = false)

    @Test
    fun whyABackgroundRunPauses() {
        val on = BackupSettings(enabled = true)
        assertNull(backupPauseReason(on, online, interactive = false))
        assertEquals(BackupPauseReason.NoNetwork, backupPauseReason(on, online.copy(hasNetwork = false), false))
        assertEquals(BackupPauseReason.WaitingForWifi, backupPauseReason(on, online.copy(isUnmetered = false), false))
        assertNull(backupPauseReason(on.copy(wifiOnly = false), online.copy(isUnmetered = false), false))
        assertEquals(BackupPauseReason.WaitingForCharging, backupPauseReason(on.copy(chargingOnly = true), online, false))
        assertEquals(BackupPauseReason.LowPowerMode, backupPauseReason(on, online.copy(isLowPowerMode = true), false))
        assertEquals(BackupPauseReason.LowBattery, backupPauseReason(on, online.copy(isBatteryLow = true), false))
        assertNull(backupPauseReason(on, online.copy(isBatteryLow = true, isCharging = true), false))
    }

    @Test
    fun aFullOrHotPhonePausesEvenWithTheAppOpen() {
        val on = BackupSettings(enabled = true)
        val full = online.copy(freeStorageBytes = 100L * 1024 * 1024)
        assertEquals(BackupPauseReason.PhoneStorageLow, backupPauseReason(on, full, interactive = true))
        assertEquals(BackupPauseReason.TooHot, backupPauseReason(on, online.copy(isOverheated = true), interactive = true))
        assertNull(backupPauseReason(on, online.copy(freeStorageBytes = 10L * 1024 * 1024 * 1024), interactive = false))
        assertNull(backupPauseReason(on, online.copy(freeStorageBytes = null), interactive = false), "unknown space doesn't pause")
    }

    @Test
    fun anOpenAppIgnoresBatteryButNotWifiOnly() {
        val on = BackupSettings(enabled = true, chargingOnly = true)
        assertNull(backupPauseReason(on, online.copy(isBatteryLow = true), interactive = true))
        assertEquals(BackupPauseReason.WaitingForWifi, backupPauseReason(on, online.copy(isUnmetered = false), interactive = true))
    }

    @Test
    fun videosNeedUnmeteredAndChargingUnlessTheAppIsOpen() {
        val on = BackupSettings(enabled = true)
        assertFalse(backupVideosAllowed(on, online, interactive = false))
        assertTrue(backupVideosAllowed(on, online.copy(isCharging = true), interactive = false))
        assertTrue(backupVideosAllowed(on, online, interactive = true))
        assertFalse(backupVideosAllowed(on, online.copy(isUnmetered = false), interactive = true))
        assertTrue(backupVideosAllowed(on.copy(videosOnCellular = true), online.copy(isUnmetered = false), interactive = true))
    }

    @Test
    fun captureTimePrefersExifWithItsOffset() {
        val wallClock = LocalDateTime(2026, 1, 2, 3, 4, 5)
        val withOffset = ImageMetadata(capturedAt = wallClock, captureUtcOffset = UtcOffset(hours = 2))
        assertEquals(1767315845000, captureTimeMs(withOffset, fallbackMs = 1, zone = TimeZone.UTC))
        assertEquals(1767323045000, captureTimeMs(ImageMetadata(capturedAt = wallClock), fallbackMs = 1, zone = TimeZone.UTC))
        assertEquals(1, captureTimeMs(ImageMetadata(), fallbackMs = 1))
        assertEquals(1, captureTimeMs(null, fallbackMs = 1))
    }

    @Test
    fun defaultsAndTheIntervalFloor() = runTest {
        val store = BackupSettingsStore(db)
        assertEquals(1.hours.inWholeMilliseconds, store.settings.value.pushIntervalMs)
        assertTrue(store.settings.value.wifiOnly)
        assertFalse(store.settings.value.videosOnCellular)
        assertFalse(store.settings.value.chargingOnly)
        assertFalse(store.settings.value.enabled)

        store.update { it.copy(enabled = true, pushIntervalMs = 5.minutes.inWholeMilliseconds) }

        assertEquals(30.minutes.inWholeMilliseconds, store.settings.value.pushIntervalMs)
        assertTrue(BackupSettingsStore(db).settings.value.enabled)
    }

    private object Offline : OutboxUploader {
        override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) = error("offline")
    }

    private fun kotlinx.coroutines.CoroutineScope.schedule() = BackupPushSchedule(
        ScheduledPushNotificationProvider(HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }), CredentialsManager()),
        OutboxSync(db, Offline, EventBus(), this).also { it.setOnline(false) },
    ) { 1_000 }

    private suspend fun onlyPushRow(): Outbox = db.outbox.selectByUniqueId(PhotobaseProtocol.BackupPushTagId)!!

    @Test
    fun thePushIsSilentRecurringAndOnTheChatAppId() = runTest {
        backgroundScope.schedule().schedule(BackupSettings(enabled = true))

        val request = OutboxSerializer.decode<SchedulePushNotificationRequest>(onlyPushRow())
        assertEquals(Uuid.parse(AppConfig.APP_ID), request.options.appId)
        assertTrue(request.options.silent)
        assertEquals(PhotobaseProtocol.BackupPushTagId, request.options.tagId)
        assertEquals(1.hours.inWholeMilliseconds, request.recurrenceInterval)
        assertEquals(1_000 + 1.hours.inWholeMilliseconds, request.sendAt.milliseconds)
        assertNull(request.options.recipients)
    }

    @Test
    fun aCancelSupersedesAQueuedSchedule() = runTest {
        val schedule = backgroundScope.schedule()
        schedule.schedule(BackupSettings(enabled = true))

        schedule.cancel()

        assertEquals(1, db.outbox.count())
        assertEquals(PhotobaseProtocol.BackupPushTagId, OutboxSerializer.decode<CancelScheduledPushRequest>(onlyPushRow()).tagId)
    }

    private fun job(interval: Long?, tag: Uuid = PhotobaseProtocol.BackupPushTagId, state: ScheduledPushNotificationState = ScheduledPushNotificationState.Scheduled) =
        ScheduledPushNotificationEntry(
            jobId = Uuid.random(),
            options = ScheduledPushNotificationOptions(Uuid.random(), Uuid.random(), tag, silent = true),
            sendAt = UnixTimeUtc(1),
            state = state,
            attemptCount = 0,
            maxAttempts = 1,
            recurrenceInterval = interval,
        )

    @Test
    fun oneJobPerIdentityIsLeftAloneByEveryPhone() = runTest {
        val schedule = backgroundScope.schedule()
        val settings = BackupSettings(enabled = true)
        val interval = settings.pushIntervalMs

        assertFalse(schedule.needsScheduling(listOf(job(interval)), settings), "another phone already made it")
        assertTrue(schedule.needsScheduling(emptyList(), settings))
        assertTrue(schedule.needsScheduling(listOf(job(interval * 2)), settings), "a changed interval corrects it")
        assertTrue(schedule.needsScheduling(listOf(job(interval), job(interval)), settings), "a race's duplicates collapse")
        assertTrue(schedule.needsScheduling(listOf(job(interval, state = ScheduledPushNotificationState.Failed)), settings))
        assertTrue(schedule.needsScheduling(listOf(job(interval, tag = Uuid.random())), settings), "another feature's push doesn't count")
    }

    @Test
    fun aDisabledBackupSchedulesNothing() = runTest {
        backgroundScope.schedule().ensure(BackupSettings(enabled = false))

        assertEquals(0, db.outbox.count())
    }
}
