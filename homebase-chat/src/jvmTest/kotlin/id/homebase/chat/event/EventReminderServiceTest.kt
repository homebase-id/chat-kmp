package id.homebase.chat.event

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.notifications.ScheduledPushOutboxUploader
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.OutboxUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Guard + enqueue/cancel semantics for [EventReminderService] (#1116). The service enqueues onto a
 * real in-memory outbox and never calls the push provider directly (the uploader does), so these
 * assertions need no HTTP fake — they inspect the queued outbox row via [OutboxSync.pendingUploadType].
 * The outbox is left **offline** so rows stay queued for inspection instead of draining. (The
 * uploader's list()-based schedule/cancel reconcile is covered by pure-function tests in
 * homebase-api's ScheduledPushScheduleDecisionTest.)
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

    /** The outbox row is keyed by a DERIVED id, not the raw messageId (avoids cross-drive collision
     *  with the event message's own outbox row) — so assertions must query by the same derived key. */
    private fun rowKey(messageId: Uuid) = EventReminderService.outboxRowKey(messageId)

    private fun descriptorStartingAt(startUtcMs: Long) =
        EventDescriptor(title = "Standup", startUtcMs = startUtcMs, timezone = "UTC")

    private class TestDeps(val outboxSync: OutboxSync, val service: EventReminderService)

    private fun deps(scope: CoroutineScope): TestDeps {
        val dbm = inMemoryDbm()
        val outboxSync = OutboxSync(dbm, NoopUploader, EventBus(), scope).also { it.setOnline(false) }
        val service = EventReminderService(
            outboxSync = outboxSync,
            preferences = EventReminderPreferences(dbm),  // default lead = 60 min
            now = { fixedNow },
        )
        return TestDeps(outboxSync, service)
    }

    @Test
    fun going_futureEvent_enqueuesSchedulePush() = runTest {
        val d = deps(backgroundScope)
        val messageId = Uuid.random()
        // Starts in 2h; lead 60m ⇒ sendAt = now + 1h > now ⇒ schedule.
        d.service.onRsvpChanged(
            conversationId = Uuid.random(),
            messageId = messageId,
            descriptor = descriptorStartingAt(fixedNow + 2 * hourMs),
            isGoing = true,
            reminderText = "Upcoming event reminder",
        )
        assertEquals(
            ScheduledPushOutboxUploader.SchedulePush,
            d.outboxSync.pendingUploadType(drive, rowKey(messageId)),
        )
    }

    @Test
    fun going_insideLeadWindow_doesNotSchedule_butDefensivelyCancels() = runTest {
        val d = deps(backgroundScope)
        val messageId = Uuid.random()
        // Starts in 30m; lead 60m ⇒ sendAt = now - 30m ≤ now ⇒ guard does NOT schedule (no
        // phantom past-fire). It defensively enqueues a cancel-by-tag instead, so any reminder set
        // earlier (when the event was further out / before an organizer moved it in) is cleared.
        d.service.onRsvpChanged(
            conversationId = Uuid.random(),
            messageId = messageId,
            descriptor = descriptorStartingAt(fixedNow + 30 * 60_000L),
            isGoing = true,
            reminderText = "Upcoming event reminder",
        )
        assertEquals(
            ScheduledPushOutboxUploader.CancelPush,
            d.outboxSync.pendingUploadType(drive, rowKey(messageId)),
        )
    }

    @Test
    fun retractWhileScheduleQueued_replacesItWithCancelByTag() = runTest {
        val d = deps(backgroundScope)
        val messageId = Uuid.random()
        val descriptor = descriptorStartingAt(fixedNow + 2 * hourMs)
        // Going (offline) → schedule row queued, never sent.
        d.service.onRsvpChanged(Uuid.random(), messageId, descriptor, isGoing = true, reminderText = "x")
        assertEquals(ScheduledPushOutboxUploader.SchedulePush, d.outboxSync.pendingUploadType(drive, rowKey(messageId)))
        // Not going → always enqueue a durable cancel-by-tag (never a silent yank, since a server job
        // could already exist). replaceEnqueue supersedes the queued schedule with the cancel row.
        d.service.onRsvpChanged(Uuid.random(), messageId, descriptor, isGoing = false, reminderText = "x")
        assertEquals(ScheduledPushOutboxUploader.CancelPush, d.outboxSync.pendingUploadType(drive, rowKey(messageId)))
    }

    @Test
    fun retractWithNoQueuedSchedule_enqueuesCancelByTag() = runTest {
        val d = deps(backgroundScope)
        val messageId = Uuid.random()
        // No prior schedule row queued (e.g. it already drained, or was scheduled on another device)
        // → cancel must be enqueued so the uploader can list()-and-cancel by tag on this device.
        d.service.onRsvpChanged(
            conversationId = Uuid.random(),
            messageId = messageId,
            descriptor = descriptorStartingAt(fixedNow + 2 * hourMs),
            isGoing = false,
            reminderText = "x",
        )
        assertEquals(
            ScheduledPushOutboxUploader.CancelPush,
            d.outboxSync.pendingUploadType(drive, rowKey(messageId)),
        )
    }
}
