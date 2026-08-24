package id.homebase.api.client.notifications

import co.touchlab.kermit.Logger
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxUploader
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** Body of a durably-enqueued *cancel*. Carries the **tagId** (not a server jobId) so it works from
 *  any device: the uploader resolves the actual job(s) via `list()` at drain time. See below. */
@Serializable
data class CancelScheduledPushRequest(val tagId: Uuid)

/** What to do with a schedule request given what the server already has for this tagId. Each action
 *  names the single job that should survive for the tag ([keepJobId]); the uploader cancels every
 *  other job for that tag so exactly one remains. */
sealed interface ScheduleAction {
    /** A job for this tagId is already scheduled at the requested time — keep it, schedule nothing. */
    data class Skip(val jobId: Uuid) : ScheduleAction

    /** A job for this tagId exists but at a different `sendAt` — move it (PUT) instead of adding a
     *  duplicate. Covers the organizer-edited-start-time case for free. */
    data class Update(val jobId: Uuid) : ScheduleAction

    /** No job for this tagId — schedule a new one. */
    data object Create : ScheduleAction

    /** The job this action keeps/creates, or null for [Create] (nothing to keep yet). */
    val keepJobId: Uuid?
        get() = when (this) {
            is Skip -> jobId
            is Update -> jobId
            Create -> null
        }
}

/**
 * Decide schedule vs update vs skip from the server's current job list. Pure + unit-tested.
 *
 * The server has **no idempotency key and no upsert**, so this client-side reconcile against
 * [existing] (keyed on the device-independent `tagId`) is what keeps a retry — or a second device —
 * from stacking duplicate jobs. When several jobs already exist for the tag (a genuine concurrent
 * two-device race), one is chosen to keep and [duplicateJobsToPrune] names the rest for the uploader
 * to cancel, so a re-affirmed Going collapses back to exactly one — duplicates no longer wait for a
 * cancel to clear.
 */
fun decideScheduleAction(
    existing: List<ScheduledPushNotificationEntry>,
    tagId: Uuid,
    sendAtMs: Long,
): ScheduleAction {
    val matches = existing.filter { it.options.tagId == tagId }
    matches.firstOrNull { it.sendAt.milliseconds == sendAtMs }?.let { return ScheduleAction.Skip(it.jobId) }
    matches.firstOrNull()?.let { return ScheduleAction.Update(it.jobId) }
    return ScheduleAction.Create
}

/** Every job the server currently holds for [tagId] — the set a cancel must remove. Pure. */
fun jobsToCancelForTag(
    existing: List<ScheduledPushNotificationEntry>,
    tagId: Uuid,
): List<Uuid> = existing.filter { it.options.tagId == tagId }.map { it.jobId }

/** Jobs for [tagId] other than [keepJobId] — the stale duplicates a schedule should prune so exactly
 *  one job survives for the tag. Pure. */
fun duplicateJobsToPrune(
    existing: List<ScheduledPushNotificationEntry>,
    tagId: Uuid,
    keepJobId: Uuid?,
): List<Uuid> = existing.filter { it.options.tagId == tagId && it.jobId != keepJobId }.map { it.jobId }

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
 *    dedup server-side. We reconcile client-side ([decideScheduleAction] + [duplicateJobsToPrune]
 *    over `list()`), which closes the retry, single-device re-RSVP, and common cross-device cases,
 *    and now collapses any accumulated duplicates back to one on the next schedule. It still does
 *    NOT close a genuine concurrent schedule where two racers both `list()` before either creates
 *    (a two-device tap at the same instant, OR — single device — a cancel enqueued while a schedule
 *    for the same tag is already checked out and mid-POST, so the cancel's `list()` runs before the
 *    schedule's job is visible → cancel no-ops, schedule creates a job that outlives the
 *    retraction). Both leave a stray job that the next cancel-by-tag (or a re-affirmed Going's
 *    prune) removes. Duplicates also count against the server's `MaxPendingPerTenant = 100` cap. A
 *    server-side unique key (client-supplied, upsert-on-conflict) would remove the race and the
 *    extra `list()` round trip entirely; that's the ask on the table.
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

        // Drain-time past guard: an offline Going can sit queued past its sendAt (a phone in airplane
        // mode until after the event). Don't post a job the server would fire late/immediately — drop
        // the row and clear any stale job for the tag. The enqueue-time guard can't cover this.
        if (request.sendAt.milliseconds <= UnixTimeUtc.now().milliseconds) {
            cancelJobs(jobsToCancelForTag(existing, tagId), tagId)
            Logger.d("$TAG: schedule tag=$tagId past sendAt at drain — cleared, not scheduling")
            return
        }

        val action = decideScheduleAction(existing, tagId, request.sendAt.milliseconds)
        // Collapse to exactly one job for the tag: cancel every existing job except the one we keep.
        cancelJobs(duplicateJobsToPrune(existing, tagId, action.keepJobId), tagId)

        when (action) {
            is ScheduleAction.Skip ->
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
        cancelJobs(jobs, request.tagId)
    }

    /** Cancel each job, swallowing a 404 (already fired/cancelled). A non-404 error propagates so the
     *  outbox retries the whole row; the retry re-lists, so already-cancelled jobs aren't re-touched. */
    private suspend fun cancelJobs(jobIds: List<Uuid>, tagId: Uuid) {
        for (jobId in jobIds) {
            try {
                provider.cancel(jobId)
                Logger.i("$TAG: cancelled job=$jobId tag=$tagId")
            } catch (e: NotFoundException) {
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
