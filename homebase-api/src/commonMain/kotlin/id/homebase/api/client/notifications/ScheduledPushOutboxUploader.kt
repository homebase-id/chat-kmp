package id.homebase.api.client.notifications

import co.touchlab.kermit.Logger
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxUploader
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** Body of a durably-enqueued *cancel*. Carries the **tagId** (not a server jobId) so it works from
 *  any device: the uploader resolves the actual job(s) via `list()` at drain time. See below. */
@Serializable
data class CancelScheduledPushRequest(val tagId: Uuid)

/** What to do with a schedule request given what the server already has for this tagId. */
sealed interface ScheduleAction {
    /** A job for this tagId is already scheduled at the requested time — nothing to do. */
    data object Skip : ScheduleAction

    /** A job for this tagId exists but at a different `sendAt` — move it (PUT) instead of adding a
     *  duplicate. Covers the organizer-edited-start-time case for free. */
    data class Update(val jobId: Uuid) : ScheduleAction

    /** No job for this tagId — schedule a new one. */
    data object Create : ScheduleAction
}

/**
 * Decide schedule vs update vs skip from the server's current job list. Pure + unit-tested.
 *
 * The server has **no idempotency key and no upsert**, so this client-side reconcile against
 * [existing] (keyed on the device-independent `tagId`) is what keeps a retry — or a second device —
 * from stacking duplicate jobs. Accumulated duplicates from a genuine concurrent-two-device race
 * aren't garbage-collected here (only the first match is reused); they're all removed together by
 * [jobsToCancelForTag] on the next cancel.
 */
fun decideScheduleAction(
    existing: List<ScheduledPushNotificationEntry>,
    tagId: Uuid,
    sendAtMs: Long,
): ScheduleAction {
    val matches = existing.filter { it.options.tagId == tagId }
    if (matches.any { it.sendAt.milliseconds == sendAtMs }) return ScheduleAction.Skip
    matches.firstOrNull()?.let { return ScheduleAction.Update(it.jobId) }
    return ScheduleAction.Create
}

/** Every job the server currently holds for [tagId] — the set a cancel must remove. Pure. */
fun jobsToCancelForTag(
    existing: List<ScheduledPushNotificationEntry>,
    tagId: Uuid,
): List<Uuid> = existing.filter { it.options.tagId == tagId }.map { it.jobId }

/**
 * Outbox uploader for **scheduled push notifications** — the shim that lets a schedule/cancel
 * survive an offline RSVP by riding the existing durable outbox queue + retry/backoff + drain-on-
 * reconnect, instead of being a fire-once HTTP call at tap time.
 *
 * Dispatched by [CompositeOutboxUploader] for [SchedulePush] / [CancelPush]; all drive uploadTypes
 * still go to `DriveOutboxUploader`.
 *
 * ## Everything keys off `tagId`, not a device-local jobId
 * The server returns only a jobId, which is useless to the user's *other* devices. But a
 * client-chosen `tagId` (here: the event `messageId`) is passed through verbatim into every
 * `list()` entry's `options.tagId`, and job listing is scoped per-app (all the user's devices see
 * the same jobs). So both schedule and cancel reconcile against `list()` filtered by `tagId`, which
 * is identical on every device — Device B's un-RSVP cancels the job Device A scheduled.
 *
 * ## Sharp edges (intentional, for the "should we properly adjust the outbox / server" discussion)
 *
 * 1. **No server upsert / idempotency key.** `schedule()` unconditionally creates a job; there is no
 *    dedup server-side. We reconcile client-side ([decideScheduleAction] over `list()`), which
 *    closes the retry and single-device-re-RSVP cases and the common cross-device case. It does NOT
 *    close a genuine concurrent-two-device schedule (both `list()` before either creates → two
 *    jobs). Duplicates also count against the server's `MaxPendingPerTenant = 100` cap, so an
 *    unmitigated retry loop could eat it — the reconcile is what prevents that. A server-side unique
 *    key (client-supplied, upsert-on-conflict) would remove the race and the extra `list()` round
 *    trip entirely; that's the ask on the table.
 *
 * 2. **An extra `list()` per schedule/cancel.** The reconcile costs one round trip each. Fine for a
 *    low-frequency RSVP action; would disappear with server-side upsert + a cancel-by-tag endpoint.
 *
 * 3. **Non-drive rows keep the outbox non-idle.** A long-queued (offline) reminder row keeps the
 *    outbox `count() != 0`, deferring the idle orphan-temp reap. Harmless (no payload temps).
 */
class ScheduledPushOutboxUploader(
    private val provider: ScheduledPushNotificationProvider,
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
        val tagId = request.options.tagId
        val existing = provider.list()
        when (val action = decideScheduleAction(existing, tagId, request.sendAt.milliseconds)) {
            ScheduleAction.Skip ->
                Logger.d("$TAG: schedule tag=$tagId already present at sendAt — skipping")

            is ScheduleAction.Update -> {
                try {
                    provider.update(action.jobId, request)
                    Logger.i("$TAG: moved job=${action.jobId} tag=$tagId to sendAt=${request.sendAt.milliseconds}")
                } catch (e: NotFoundException) {
                    // The matched job fired/was cancelled between our list() and update() — create.
                    val jobId = provider.schedule(request)
                    Logger.i("$TAG: update target gone, scheduled fresh job=$jobId tag=$tagId")
                }
            }

            ScheduleAction.Create -> {
                val jobId = provider.schedule(request)
                Logger.i("$TAG: scheduled job=$jobId tag=$tagId sendAt=${request.sendAt.milliseconds}")
            }
        }
    }

    private suspend fun cancel(record: Outbox) {
        val request = OdinSystemSerializer.deserialize<CancelScheduledPushRequest>(
            record.json.decodeToString(),
        )
        val jobs = jobsToCancelForTag(provider.list(), request.tagId)
        if (jobs.isEmpty()) {
            Logger.d("$TAG: cancel tag=${request.tagId} — no matching job on server")
            return
        }
        for (jobId in jobs) {
            try {
                provider.cancel(jobId)
                Logger.i("$TAG: cancelled job=$jobId tag=${request.tagId}")
            } catch (e: NotFoundException) {
                // Already fired / already cancelled — the reminder is gone either way.
                Logger.d("$TAG: cancel job=$jobId returned 404 — already handled")
            }
        }
    }

    companion object {
        private const val TAG = "ScheduledPushOutboxUploader"

        // uploadType ids — continue the DriveOutboxUploader sequence (1..9) without overlapping it.
        const val SchedulePush = 10L
        const val CancelPush = 11L
    }
}
