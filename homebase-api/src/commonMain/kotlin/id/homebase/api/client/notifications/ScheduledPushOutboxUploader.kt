package id.homebase.api.client.notifications

import co.touchlab.kermit.Logger
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxUploader
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** Body of a durably-enqueued *cancel* of a previously-scheduled push. Carries only the
 *  server-assigned jobId; the outbox row's `uniqueId` ties it back to the feature that scheduled it. */
@Serializable
data class CancelScheduledPushRequest(val jobId: Uuid)

/**
 * Outbox uploader for **scheduled push notifications** — the shim that lets a schedule/cancel
 * survive an offline RSVP by riding the existing durable outbox queue + retry/backoff + drain-on-
 * reconnect, instead of being a fire-once HTTP call at tap time.
 *
 * Dispatched by [CompositeOutboxUploader] for [SchedulePush] / [CancelPush]; all drive uploadTypes
 * still go to `DriveOutboxUploader`. The row's `json` is a serialized
 * [SchedulePushNotificationRequest] (schedule) or [CancelScheduledPushRequest] (cancel); the row's
 * `uniqueId` is the feature key (event reminders use `messageId`).
 *
 * ## Sharp edges (intentional, for the "should we properly adjust the outbox" discussion)
 *
 * 1. **Double-schedule on lost ack.** `/notify/push/schedule` has no idempotency key, so a
 *    schedule that succeeds server-side but whose ack is lost would, on the outbox retry, schedule
 *    a *second* job. Mitigated here: we persist the jobId to [ScheduledPushJobStore] on success and
 *    short-circuit if it's already present; and on any retry (`checkOutCount > 0`) we reconcile
 *    against the server via `list()` and adopt a matching job (same `tagId` + `sendAt`) instead of
 *    re-scheduling. This narrows but does not fully close the window (a crash between a successful
 *    schedule and the `list()`-visibility of that job could still double-book). A first-class fix
 *    would be a server idempotency key or the outbox capturing the uploader's return value.
 *
 * 2. **jobId lives outside the outbox.** The row can't hold the server's response, so cancel/update
 *    need the side-band [ScheduledPushJobStore]. That's extra state the drive path never needs.
 *
 * 3. **Non-drive rows keep the outbox non-idle.** A long-queued (offline) reminder row keeps the
 *    outbox `count() != 0`, which defers the idle orphan-temp reap. Harmless (reminders carry no
 *    payload temps) but worth knowing.
 */
class ScheduledPushOutboxUploader(
    private val provider: ScheduledPushNotificationProvider,
    private val jobStore: ScheduledPushJobStore,
) : OutboxUploader {

    override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) {
        when (outboxRecord.uploadType) {
            SchedulePush -> schedule(outboxRecord)
            CancelPush -> cancel(outboxRecord)
            else -> Logger.w("$TAG: unexpected uploadType=${outboxRecord.uploadType} — ignoring")
        }
    }

    private suspend fun schedule(record: Outbox) {
        val request = OdinSystemSerializer.deserialize<SchedulePushNotificationRequest>(
            record.json.decodeToString(),
        )
        // Sharp edge #1 — idempotency. Fast path: we already scheduled + persisted this row's job.
        if (jobStore.get(record.uniqueId) != null) {
            Logger.d("$TAG: schedule uniqueId=${record.uniqueId} already has a jobId — skipping")
            return
        }
        // Retry path: a previous attempt may have scheduled server-side but died before persisting
        // the jobId. Adopt an existing matching job rather than double-booking.
        if (record.checkOutCount > 0) {
            val existing = runCatching { provider.list() }.getOrNull()?.firstOrNull {
                it.options.tagId == request.options.tagId && it.sendAt == request.sendAt
            }
            if (existing != null) {
                Logger.i("$TAG: schedule uniqueId=${record.uniqueId} adopting server job=${existing.jobId} (lost-ack recovery)")
                jobStore.put(record.uniqueId, existing.jobId)
                return
            }
        }
        val jobId = provider.schedule(request)
        jobStore.put(record.uniqueId, jobId)
        Logger.i("$TAG: scheduled uniqueId=${record.uniqueId} jobId=$jobId sendAt=${request.sendAt.milliseconds}")
    }

    private suspend fun cancel(record: Outbox) {
        val request = OdinSystemSerializer.deserialize<CancelScheduledPushRequest>(
            record.json.decodeToString(),
        )
        try {
            provider.cancel(request.jobId)
            Logger.i("$TAG: cancelled jobId=${request.jobId} (uniqueId=${record.uniqueId})")
        } catch (e: NotFoundException) {
            // 404 == already fired / already cancelled / unknown — the client can't tell these
            // apart and doesn't need to: the reminder is gone either way. Treat as handled.
            Logger.d("$TAG: cancel jobId=${request.jobId} returned 404 — already handled")
        }
        jobStore.delete(record.uniqueId)
    }

    companion object {
        private const val TAG = "ScheduledPushOutboxUploader"

        // uploadType ids — continue the DriveOutboxUploader sequence (1..9) without overlapping it.
        const val SchedulePush = 10L
        const val CancelPush = 11L
    }
}
