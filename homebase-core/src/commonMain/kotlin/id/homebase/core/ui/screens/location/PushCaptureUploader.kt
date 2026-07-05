package id.homebase.core.ui.screens.location

import co.touchlab.kermit.Logger
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.core.location.PushLocationCapture
import id.homebase.core.location.tracking.GpsFixResult
import id.homebase.core.ui.screens.location.model.hourStartMs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Push-wake capture+upload orchestrator (#987). The problem it closes: during a background
 * push wake the websocket never connects, so `OutboxSync.isOnline` stays false and an
 * enqueued hour-file would sit in the outbox until the next foreground connect — the
 * sender's "Who you can locate" stays stale even though the capture ran.
 *
 * Flow: backlog drain kicked IMMEDIATELY (the GPS radio wait is free network time — the
 * two don't contend, so stranded rows upload while the fix acquires; and every wake drains
 * the backlog even when the capture gate is closed or the flush is rate-gated) ∥ gated
 * capture (all existing gates respected) → find the ACTUAL pending hour-file row (the
 * capture may have enqueued nothing — served-from-cache / rate-gated — while an EARLIER
 * stranded row may still be waiting; the row is the truth) → re-kick + row-verified
 * confirmation. Backlog POSTs beyond the awaited fresh-row confirm race process freeze
 * exactly as before — best-effort; rows survive for the next wake.
 *
 * Why row-verified: the hour-file uid is deterministic (`locationHourFileUid`), and the
 * EventBus replays one event — a stale `ItemCompleted` for the SAME uid from an earlier
 * flush could false-confirm. OutboxSync deletes the row BEFORE emitting, so row-gone is
 * authoritative.
 *
 * Constructor takes lambda/flow seams (not the services) so the orchestration is unit
 * testable without the uploader/outbox graph; AppModule wires the real calls.
 */
class PushCaptureUploader(
    /** `{ locationService.forceCaptureIfTracking(GpsRequestReason.PushReceived) }` — all gates inside. */
    private val capture: suspend () -> GpsFixResult?,
    /** `{ uid -> outboxSync.pendingUploadType(locationDriveId, uid) != null }` */
    private val pendingRow: suspend (Uuid) -> Boolean,
    /** `{ outboxSync.send(force = true) }` — fire-and-return; completion observed via events/row. */
    private val drain: suspend () -> Unit,
    private val events: Flow<BackendEvent>,
    private val locationDriveId: Uuid,
    /** `{ hourStartMs -> locationHourFileUid(deviceId.value, hourStartMs) }` */
    private val hourUid: (Long) -> Uuid,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : PushLocationCapture {

    private val logger = Logger.withTag(TAG)

    override suspend fun captureAndUpload(budgetMs: Long) {
        runCatching {
            val start = nowMs()
            withTimeoutOrNull(budgetMs) { run(start, budgetMs) }
                ?: logger.w { "drain: budget exhausted (budgetMs=$budgetMs)" }
        }.onFailure { e ->
            logger.w(throwable = e) { "captureAndUpload failed (best-effort — push never fails over it)" }
        }
    }

    private suspend fun run(startMs: Long, budgetMs: Long): Unit = coroutineScope {
        // Backlog drain kicks IMMEDIATELY, concurrent with the capture: the GPS radio wait
        // (up to 5s) is free network time, and this also guarantees every push wake drains
        // stranded rows (old hour-files, chat queued offline) even when the capture gate is
        // closed or the flush is rate-gated — paths that previously sent nothing. drain()
        // (send(force=true)) only SPAWNS the worker on the outbox's own scope, so this
        // launch returns fast and never holds the wake budget hostage; the connectivity
        // circuit-breaker bounds the genuinely-offline cost to one uncharged probe.
        // UNDISPATCHED: run the kick to its first suspension point NOW — a plain launch
        // only queues it, and on a single-threaded dispatcher capture() would start first.
        launch(start = CoroutineStart.UNDISPATCHED) {
            logger.i { "drain: backlog kick at wake start" }
            runCatching { drain() }
                .onFailure { logger.w(throwable = it) { "drain: backlog kick failed" } }
        }

        val fix = capture()
        logger.i {
            "capture(PushReceived): " + when (fix) {
                null -> "skipped (gate closed — see forceCaptureIfTracking line)"
                is GpsFixResult.Success ->
                    "fix src=${fix.point.src} ageMs=${nowMs() - fix.point.t}"
                else -> "$fix"
            }
        }
        if (fix == null) return@coroutineScope // tracking off / no consumer — no location rows expected

        // The fix's own timestamp decides its hour file; the current hour covers the
        // hour-boundary and served-from-cache cases. A pending row may also predate this
        // push entirely (a stranded earlier upload) — the row check is the truth.
        val candidates = buildList {
            if (fix is GpsFixResult.Success) add(hourUid(hourStartMs(fix.point.t)))
            val current = hourUid(hourStartMs(nowMs()))
            if (current !in this) add(current)
        }
        val pendingUid = candidates.firstOrNull { pendingRow(it) }
        if (pendingUid == null) {
            logger.i { "drain: nothing pending (rate-gated, deferred, served-from-cache, or already sent)" }
            return@coroutineScope
        }

        val confirmed: Boolean? = coroutineScope {
            // Subscribe BEFORE draining so the completion can't slip between drain and collect.
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                awaitRowConfirmed(pendingUid)
            }
            drain()
            val remaining = (budgetMs - (nowMs() - startMs)).coerceAtLeast(500)
            withTimeoutOrNull(remaining) { waiter.await() }
                .also { if (it == null) waiter.cancel() }
        }
        val elapsed = nowMs() - startMs
        when (confirmed) {
            true -> logger.i { "drain: confirmed uid=$pendingUid elapsedMs=$elapsed" }

            // Terminal failure/drop — the row (if still there) retries on the next wake
            // (worker leg / next push); the backoff makes waiting out the budget waste.
            false -> logger.i { "drain: attempt failed/dropped uid=$pendingUid stillPending=${pendingRow(pendingUid)} elapsedMs=$elapsed" }

            // Timeout — the POST may still have landed with the event losing the race:
            // row-gone is the authoritative fallback check.
            null -> {
                val stillPending = pendingRow(pendingUid)
                if (!stillPending) {
                    logger.i { "drain: confirmed via row-gone after event timeout uid=$pendingUid elapsedMs=$elapsed" }
                } else {
                    logger.i { "drain: NOT confirmed uid=$pendingUid stillPending=true elapsedMs=$elapsed budgetMs=$budgetMs" }
                }
            }
        }
    }

    /**
     * Await a terminal outbox event for (locationDrive, uid): true on a confirmed completion,
     * false on failed/dropped (early exit — the backoff makes waiting out the budget waste).
     *
     * A completed event only counts once the row is actually gone — the stale-replay guard:
     * EventBus replay=1 can hand a NEW subscriber an OLD ItemCompleted for this same
     * deterministic uid from an earlier flush; a stale event maps to null and collection
     * continues on the SAME subscription (re-subscribing would replay the same stale event
     * forever). Row-gone is authoritative: OutboxSync deletes the row before emitting. The
     * enclosing withTimeoutOrNull bounds the whole wait.
     */
    private suspend fun awaitRowConfirmed(uid: Uuid): Boolean =
        events.filterIsInstance<BackendEvent.OutboxEvent>()
            .mapNotNull { event ->
                when {
                    event is BackendEvent.OutboxEvent.ItemCompleted &&
                        event.driveId == locationDriveId && event.uniqueId == uid ->
                        if (!pendingRow(uid)) true else null // stale replay — keep collecting

                    event is BackendEvent.OutboxEvent.ItemFailed &&
                        event.driveId == locationDriveId && event.uniqueId == uid -> false

                    event is BackendEvent.OutboxEvent.OutboxItemDropped &&
                        event.driveId == locationDriveId && event.uniqueId == uid -> false

                    else -> null
                }
            }
            .first()

    private companion object {
        const val TAG = "PushCapture"
    }
}
