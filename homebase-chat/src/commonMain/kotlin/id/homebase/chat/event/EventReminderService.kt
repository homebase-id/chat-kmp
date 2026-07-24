package id.homebase.chat.event

import co.touchlab.kermit.Logger
import id.homebase.api.client.notifications.CancelScheduledPushRequest
import id.homebase.api.client.notifications.SchedulePushNotificationRequest
import id.homebase.api.client.notifications.ScheduledPushJobStore
import id.homebase.api.client.notifications.ScheduledPushNotificationOptions
import id.homebase.api.client.notifications.ScheduledPushOutboxUploader
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.sync.database.OutboxSync
import id.homebase.chat.services.ChatProtocol
import kotlin.uuid.Uuid

private const val TAG = "EventReminderService"

/**
 * Schedules / cancels the RSVP-"Going" self-reminder push for an event (#1116).
 *
 * **Durable + offline-safe by design.** Both the schedule and the cancel go through the outbox
 * ([OutboxSync.enqueueScheduledPush] / [OutboxSync.enqueueCancelScheduledPush]) rather than a direct
 * HTTP call, so an RSVP made offline still lands its reminder once connectivity returns — the same
 * guarantee the reaction itself gets. The actual `/notify/push/schedule` call happens in
 * [ScheduledPushOutboxUploader] when the row drains. See that class for the sharp edges this shim
 * accepts (chiefly: no server idempotency key → a lost ack can double-book, mitigated but not
 * eliminated).
 *
 * ## Keying
 * Reminder outbox rows use a reserved pseudo-drive ([EVENT_REMINDER_PSEUDO_DRIVE]) and
 * `uniqueId = messageId`. That gives one reminder per event message (the outbox
 * `UNIQUE(driveId, uniqueId)`), lets a retract of a *still-queued* schedule simply yank the row,
 * and keys the returned jobId in [ScheduledPushJobStore] for a later cancel.
 *
 * ## Scope of #1116 (deliberately not the whole lifecycle)
 * Ships steps 1–2: Going → schedule, away-from-Going (Not going / retract / Maybe) → cancel.
 * **Maybe = no reminder** (Going-only, per the issue's default). **Reschedule on an organizer's
 * start-time edit (step 4) is NOT handled here** — it needs a drift-detection trigger (render-time
 * reconcile or a message-update observer) orthogonal to this durability work, and is left as a
 * follow-up. The privacy note is honoured: [reminderText] is generic (no event title) because it
 * rides the push provider in cleartext; the deep-link tap opens the real (decrypted) event.
 */
class EventReminderService(
    private val outboxSync: OutboxSync,
    private val jobStore: ScheduledPushJobStore,
    private val preferences: EventReminderPreferences,
    private val now: () -> Long = { UnixTimeUtc.now().milliseconds },
) {

    /**
     * Reconcile the reminder to the RSVP the user just landed on. [isGoing] is the *resulting*
     * state (Going → true; Not going / Maybe / retracted → false). [reminderText] is resolved by
     * the caller (a composable) so it stays localizable without pulling compose-resources in here.
     */
    suspend fun onRsvpChanged(
        conversationId: Uuid,
        messageId: Uuid,
        descriptor: EventDescriptor,
        isGoing: Boolean,
        reminderText: String,
    ) {
        if (isGoing) {
            scheduleReminder(conversationId, messageId, descriptor, reminderText)
        } else {
            cancelReminder(messageId)
        }
    }

    private suspend fun scheduleReminder(
        conversationId: Uuid,
        messageId: Uuid,
        descriptor: EventDescriptor,
        reminderText: String,
    ) {
        val sendAt = descriptor.startUtcMs - preferences.leadMillis
        // Guard: don't schedule once we're already inside the lead window or the event is past —
        // the server won't meaningfully fire a past sendAt (one-shot). Also drop any stale reminder.
        if (sendAt <= now()) {
            Logger.d(tag = TAG) { "skip schedule msg=$messageId — sendAt=$sendAt already within lead window/past" }
            cancelReminder(messageId)
            return
        }
        val options = ScheduledPushNotificationOptions(
            appId = ChatProtocol.ChatAppId,   // reuse the chat push identity so the tap deep-links…
            typeId = conversationId,          // …to the conversation…
            tagId = messageId,                // …and opens this event message (EventDetailDialog).
            silent = false,
            recipients = null,                // self-notification: the RSVP-er's own subscriptions.
            unEncryptedMessage = reminderText,
        )
        val request = SchedulePushNotificationRequest(
            options = options,
            sendAt = UnixTimeUtc(sendAt),
            recurrenceInterval = null,        // one-shot.
        )
        val result = outboxSync.enqueueScheduledPush(EVENT_REMINDER_PSEUDO_DRIVE, messageId, request)
        Logger.i(tag = TAG) { "scheduleReminder msg=$messageId sendAt=$sendAt enqueue=$result" }
    }

    private suspend fun cancelReminder(messageId: Uuid) {
        // If the schedule is still sitting in the outbox (never sent — e.g. RSVP'd offline then
        // retracted before reconnect), just remove it: nothing was scheduled server-side.
        if (outboxSync.pendingUploadType(EVENT_REMINDER_PSEUDO_DRIVE, messageId)
            == ScheduledPushOutboxUploader.SchedulePush
        ) {
            val outcome = outboxSync.cancelPending(EVENT_REMINDER_PSEUDO_DRIVE, messageId)
            Logger.i(tag = TAG) { "cancelReminder msg=$messageId removed still-queued schedule ($outcome)" }
            return
        }
        // Otherwise the schedule already drained; cancel the server job durably (needs its jobId).
        val jobId = jobStore.get(messageId)
        if (jobId == null) {
            Logger.d(tag = TAG) { "cancelReminder msg=$messageId — no jobId on record, nothing to cancel" }
            return
        }
        val result = outboxSync.enqueueCancelScheduledPush(
            EVENT_REMINDER_PSEUDO_DRIVE, messageId, CancelScheduledPushRequest(jobId),
        )
        Logger.i(tag = TAG) { "cancelReminder msg=$messageId jobId=$jobId enqueue=$result" }
    }

    companion object {
        /**
         * Reserved, non-real drive id used only to key reminder rows in the outbox table. It never
         * addresses a drive — the push uploader ignores it — so it can't collide with a real chat
         * drive, and its outbox events don't match any conversation's per-drive UI.
         */
        val EVENT_REMINDER_PSEUDO_DRIVE: Uuid = Uuid.parse("00000000-0000-0000-0000-0000e4e4e4e4")
    }
}
