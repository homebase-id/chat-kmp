package id.homebase.chat.event

import co.touchlab.kermit.Logger
import id.homebase.api.client.notifications.CancelScheduledPushRequest
import id.homebase.api.client.notifications.SchedulePushNotificationRequest
import id.homebase.api.client.notifications.ScheduledPushNotificationOptions
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.crypto.Md5
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
        val result = outboxSync.enqueueScheduledPush(EVENT_REMINDER_PSEUDO_DRIVE, outboxRowKey(messageId), request)
        Logger.i(tag = TAG) { "scheduleReminder msg=$messageId sendAt=$sendAt enqueue=$result" }
    }

    private suspend fun cancelReminder(messageId: Uuid) {
        // Always enqueue a durable cancel-by-tag — never try to "yank" a still-queued schedule as a
        // shortcut. A yank is only safe if NO server job exists, but one can already exist (an
        // earlier Going that drained; a re-Going that superseded a pending cancel; another device),
        // and the yank can't tell — so it would silently drop a needed cancel and the reminder would
        // fire despite the retraction. This is correct in every case instead: `replaceEnqueue`
        // supersedes any still-queued schedule row for this key, and the uploader's cancel() is a
        // cheap list()-driven no-op when the server holds no job. Keying on the tag (not a local
        // jobId) is what makes cross-device retraction work.
        val result = outboxSync.enqueueCancelScheduledPush(
            EVENT_REMINDER_PSEUDO_DRIVE, outboxRowKey(messageId), CancelScheduledPushRequest(tagId = messageId),
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

        /**
         * Outbox row `uniqueId` for a message's reminder — a DERIVED id, deliberately NOT the raw
         * [messageId]. The event message's own drive upload already uses `uniqueId = messageId`, and
         * parts of the outbox (dependency resolution: `existsByUniqueId` / `selectByUniqueId`) match
         * on uniqueId **across drives** — so reusing the messageId here would let a reminder row and
         * the message's own row be confused. Deriving keeps one reminder row per message (the
         * `UNIQUE(driveId, uniqueId)` dedup and `cancelPending` targeting still hold) with no
         * collision. The push `tagId` stays the raw [messageId] — that's the device-independent
         * server correlation key, unaffected by this.
         */
        fun outboxRowKey(messageId: Uuid): Uuid = Md5.toGuidId("event-reminder:$messageId")
    }
}
