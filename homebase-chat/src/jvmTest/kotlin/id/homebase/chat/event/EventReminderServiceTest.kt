package id.homebase.chat.event

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.notifications.ScheduledPushJobStore
import id.homebase.api.client.notifications.ScheduledPushOutboxUploader
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * Guard + enqueue/cancel semantics for [EventReminderService] (#1116). The service enqueues onto a
 * real in-memory outbox and never calls the push provider directly (the uploader does), so these
 * assertions need no HTTP fake — they inspect the queued outbox row via [OutboxSync.pendingUploadType].
 * The outbox is left **offline** so rows stay queued for inspection instead of draining.
 */
class EventReminderServiceTest {

    private object NoopUploader : OutboxUploader {
        override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) = Unit
    }

    private fun inMemoryDbm(): DatabaseManager = DatabaseManager({
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OdinDatabase.Schema.create(driver)
        driver
    })

    private val fixedNow = 1_700_000_000_000L
    private val hourMs = 3_600_000L
    private val drive = EventReminderService.EVENT_REMINDER_PSEUDO_DRIVE

    private fun descriptorStartingAt(startUtcMs: Long) =
        EventDescriptor(title = "Standup", startUtcMs = startUtcMs, timezone = "UTC")

    private fun TestDeps.service() = EventReminderService(
        outboxSync = outboxSync,
        jobStore = jobStore,
        preferences = preferences,     // default lead = 60 min
        now = { fixedNow },
    )

    private class TestDeps(
        val dbm: DatabaseManager,
        val outboxSync: OutboxSync,
        val jobStore: ScheduledPushJobStore,
        val preferences: EventReminderPreferences,
    )

    private suspend fun deps(scope: kotlinx.coroutines.CoroutineScope): TestDeps {
        val dbm = inMemoryDbm()
        val outboxSync = OutboxSync(dbm, NoopUploader, EventBus(), scope).also { it.setOnline(false) }
        return TestDeps(dbm, outboxSync, ScheduledPushJobStore(dbm), EventReminderPreferences(dbm))
    }

    @Test
    fun going_futureEvent_enqueuesSchedulePush() = runTest {
        val d = deps(backgroundScope)
        val messageId = Uuid.random()
        // Starts in 2h; lead 60m ⇒ sendAt = now + 1h > now ⇒ schedule.
        d.service().onRsvpChanged(
            conversationId = Uuid.random(),
            messageId = messageId,
            descriptor = descriptorStartingAt(fixedNow + 2 * hourMs),
            isGoing = true,
            reminderText = "Upcoming event reminder",
        )
        assertEquals(
            ScheduledPushOutboxUploader.SchedulePush,
            d.outboxSync.pendingUploadType(drive, messageId),
        )
    }

    @Test
    fun going_insideLeadWindow_doesNotSchedule() = runTest {
        val d = deps(backgroundScope)
        val messageId = Uuid.random()
        // Starts in 30m; lead 60m ⇒ sendAt = now - 30m ≤ now ⇒ guard skips (no phantom past-fire).
        d.service().onRsvpChanged(
            conversationId = Uuid.random(),
            messageId = messageId,
            descriptor = descriptorStartingAt(fixedNow + 30 * 60_000L),
            isGoing = true,
            reminderText = "Upcoming event reminder",
        )
        assertNull(d.outboxSync.pendingUploadType(drive, messageId))
    }

    @Test
    fun retractWhileStillQueued_removesTheScheduleRow() = runTest {
        val d = deps(backgroundScope)
        val messageId = Uuid.random()
        val descriptor = descriptorStartingAt(fixedNow + 2 * hourMs)
        val svc = d.service()
        // Going (offline) → schedule row queued, never sent.
        svc.onRsvpChanged(Uuid.random(), messageId, descriptor, isGoing = true, reminderText = "x")
        assertEquals(ScheduledPushOutboxUploader.SchedulePush, d.outboxSync.pendingUploadType(drive, messageId))
        // Not going before it ever drained → the queued schedule is simply yanked; nothing scheduled.
        svc.onRsvpChanged(Uuid.random(), messageId, descriptor, isGoing = false, reminderText = "x")
        assertNull(d.outboxSync.pendingUploadType(drive, messageId))
    }

    @Test
    fun jobStore_roundTrips() = runTest {
        val d = deps(backgroundScope)
        val key = Uuid.random()
        val jobId = Uuid.random()
        assertNull(d.jobStore.get(key))
        d.jobStore.put(key, jobId)
        assertEquals(jobId, d.jobStore.get(key))
        d.jobStore.delete(key)
        assertNull(d.jobStore.get(key))
    }
}
