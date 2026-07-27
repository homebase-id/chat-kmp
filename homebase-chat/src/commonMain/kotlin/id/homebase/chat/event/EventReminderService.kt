package id.homebase.chat.event

import co.touchlab.kermit.Logger
import id.homebase.api.client.notifications.CancelScheduledPushRequest
import id.homebase.api.client.notifications.SchedulePushNotificationRequest
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
 * **Durable + offline-safe + multi-device.** Both schedule and cancel go through the outbox
 * ([OutboxSync.enqueueScheduledPush] / [OutboxSync.enqueueCancelScheduledPush]) rather than a direct
 * HTTP call, so an RSVP made offline still lands once connectivity returns — the same guarantee the
 * reaction itself gets. The actual `/notify/push/schedule` work happens in
 * [ScheduledPushOutboxUploader] when the row drains.
 *
 * ## Everything keys off `tagId = messageId` (device-independent)
 * The push's `tagId` is the event `messageId`, which is identical on all of the user's devices and
 * round-trips in the server's job `list()`. So a reminder scheduled on Device A can be found and
 * cancelled from Device B by tag — no device-local jobId is stored anywhere. The uploader does the
 * `list()`-based reconcile (schedule → skip/update/create; cancel → remove every job for the tag).
 *
 * ## Keying in the outbox
 * Reminder rows use a reserved pseudo-drive ([EVENT_REMINDER_PSEUDO_DRIVE]) with
 * `uniqueId = messageId`, so there's one pending reminder op per event and `replaceEnqueue`
 * (last-writer-wins) lets a quick Going→NotGoing supersede a still-queued schedule.
 *
 * ## Scope of #1116
 * Going → schedule, away-from-Going (Not going / retract / Maybe) → cancel. **Maybe = no reminder**
 * (Going-only, per the issue default). Reschedule on an organizer's start-time edit is handled
 * opportunistically: the uploader's schedule reconcile PUTs an existing job to the new `sendAt`, so
 * whenever a Going is re-affirmed against an edited event the reminder moves. The privacy note is
 * honoured: [reminderText] is generic (no event title) since it rides the push provider in
 * cleartext; the deep-link tap opens the real (decrypted) event.
 */
class EventReminderService(
    private val outboxSync: OutboxSync,
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
        // the server won't meaningfully fire a past sendAt. Also drop any stale reminder.
        if (sendAt <= now()) {
            Logger.d(tag = TAG) { "skip schedule msg=$messageId — sendAt=$sendAt already within lead window/past" }
            cancelReminder(messageId)
            return
        }
        val options = ScheduledPushNotificationOptions(
            appId = ChatProtocol.ChatAppId,   // reuse the chat push identity so the tap deep-links…
            typeId = conversationId,          // …to the conversation…
            tagId = messageId,                // …opens this event message AND is our reconcile key.
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
        // If a schedule is still sitting in the outbox (never sent — e.g. RSVP'd offline then
        // retracted before reconnect), just remove it: nothing was scheduled server-side, so there's
        // nothing to cancel and no need for a list() round-trip when the cancel would drain.
        if (outboxSync.pendingUploadType(EVENT_REMINDER_PSEUDO_DRIVE, messageId)
            == ScheduledPushOutboxUploader.SchedulePush
        ) {
            val outcome = outboxSync.cancelPending(EVENT_REMINDER_PSEUDO_DRIVE, messageId)
            Logger.i(tag = TAG) { "cancelReminder msg=$messageId removed still-queued schedule ($outcome)" }
            return
        }
        // Otherwise durably enqueue a cancel-by-tag; the uploader resolves the actual job(s) via
        // list() at drain time, so this works even for a reminder another device scheduled.
        val result = outboxSync.enqueueCancelScheduledPush(
            EVENT_REMINDER_PSEUDO_DRIVE, messageId, CancelScheduledPushRequest(tagId = messageId),
        )
        Logger.i(tag = TAG) { "cancelReminder msg=$messageId enqueue cancel-by-tag=$result" }
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
