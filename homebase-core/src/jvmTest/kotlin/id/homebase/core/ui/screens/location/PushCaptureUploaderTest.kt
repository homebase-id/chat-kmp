package id.homebase.core.ui.screens.location

import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.core.location.tracking.GpsFixResult
import id.homebase.core.location.tracking.RawLocationPoint
import id.homebase.core.ui.screens.location.model.HOUR_MS
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Pins the push-wake capture+upload orchestration (#987): gate-closed fast exit, pending-row
 * gating, the stale-replay guard (EventBus replay=1 can hand a NEW subscriber an OLD
 * ItemCompleted for the same deterministic hour-file uid), the row-gone timeout fallback,
 * the failed/dropped early exit, and the hour-boundary uid candidates.
 */
class PushCaptureUploaderTest {

    private val driveId = Uuid.parse("2e191a14-8640-4ebc-b0c8-aaac913f6fa8")
    private val otherDrive = Uuid.parse("9ff813af-f2d6-1e2f-9b9d-b189e72d1a11")
    private val nowFixed = 1_800_000_000_000L // mid-hour

    private fun uidFor(hourStart: Long): Uuid = Uuid.parse(
        "00000000-0000-4000-8000-" + (hourStart / HOUR_MS).toString().padStart(12, '0')
    )

    private fun fix(t: Long) = GpsFixResult.Success(
        RawLocationPoint(t = t, lat = 52.0, lon = 13.0, acc = 10.0, src = "gps", fg = false)
    )

    private class Harness(
        val events: MutableSharedFlow<BackendEvent> = MutableSharedFlow(replay = 1, extraBufferCapacity = 64),
    ) {
        var captureResult: GpsFixResult? = null
        val pending = mutableSetOf<Uuid>()
        var drainCalls = 0
        var onDrain: suspend () -> Unit = {}
    }

    private fun buildUploader(h: Harness, nowMs: Long = nowFixed): PushCaptureUploader =
        PushCaptureUploader(
            capture = { h.captureResult },
            pendingRow = { uid -> uid in h.pending },
            drain = { h.drainCalls++; h.onDrain() },
            events = h.events,
            locationDriveId = driveId,
            hourUid = ::uidFor,
            nowMs = { nowMs },
        )

    @Test
    fun gateClosed_returnsFast_noDrain() = runTest {
        val h = Harness()
        h.captureResult = null
        buildUploader(h).captureAndUpload(budgetMs = 10_000)
        assertEquals(0, h.drainCalls)
    }

    @Test
    fun noPendingRow_returnsFast_noDrain() = runTest {
        val h = Harness()
        h.captureResult = fix(nowFixed - 1_000)
        // pending stays empty: rate-gated / served-from-cache / already sent.
        buildUploader(h).captureAndUpload(budgetMs = 10_000)
        assertEquals(0, h.drainCalls)
    }

    @Test
    fun happyPath_drainConfirms() = runTest {
        val h = Harness()
        val uid = uidFor(nowFixed - nowFixed % HOUR_MS)
        h.captureResult = fix(nowFixed - 1_000)
        h.pending += uid
        h.onDrain = {
            // Simulate the outbox worker: delete row, then emit (OutboxSync order).
            launch {
                h.pending -= uid
                h.events.emit(BackendEvent.OutboxEvent.ItemCompleted(driveId, uid))
            }
        }

        buildUploader(h).captureAndUpload(budgetMs = 10_000)

        assertEquals(1, h.drainCalls)
        assertFalse(uid in h.pending)
    }

    @Test
    fun staleReplayedCompletion_doesNotFalseConfirm() = runTest {
        val h = Harness()
        val uid = uidFor(nowFixed - nowFixed % HOUR_MS)
        // A STALE ItemCompleted for this same deterministic uid sits in the replay cache
        // from an earlier flush of the same hour.
        h.events.emit(BackendEvent.OutboxEvent.ItemCompleted(driveId, uid))
        h.captureResult = fix(nowFixed - 1_000)
        h.pending += uid
        var genuineCompletionSent = false
        h.onDrain = {
            launch {
                // The genuine completion arrives later; the stale replay must be ignored
                // because the row is still pending when it's observed.
                genuineCompletionSent = true
                h.pending -= uid
                h.events.emit(BackendEvent.OutboxEvent.ItemCompleted(driveId, uid))
            }
        }

        buildUploader(h).captureAndUpload(budgetMs = 10_000)

        assertTrue(genuineCompletionSent, "must wait for the genuine completion, not the replay")
        assertFalse(uid in h.pending)
    }

    @Test
    fun eventLost_rowGoneAtTimeout_isTreatedAsLanded() = runTest {
        val h = Harness()
        val uid = uidFor(nowFixed - nowFixed % HOUR_MS)
        h.captureResult = fix(nowFixed - 1_000)
        h.pending += uid
        h.onDrain = {
            // POST lands (row gone) but the event never reaches the collector.
            h.pending -= uid
        }

        // Completes at the inner event-timeout instead of hanging: virtual time advances
        // automatically in runTest while the collector waits.
        buildUploader(h).captureAndUpload(budgetMs = 5_000)

        assertEquals(1, h.drainCalls)
        assertFalse(uid in h.pending) // the timeout fallback observed row-gone (logged as landed)
    }

    @Test
    fun itemFailed_exitsEarly() = runTest {
        val h = Harness()
        val uid = uidFor(nowFixed - nowFixed % HOUR_MS)
        h.captureResult = fix(nowFixed - 1_000)
        h.pending += uid
        h.onDrain = {
            launch { h.events.emit(BackendEvent.OutboxEvent.ItemFailed(driveId, uid)) }
        }

        val start = currentTime
        buildUploader(h).captureAndUpload(budgetMs = 60_000)
        val elapsedVirtual = currentTime - start

        assertTrue(elapsedVirtual < 60_000, "failed attempt must exit early, not wait out the budget")
        assertTrue(uid in h.pending, "row survives for the next wake's retry")
    }

    @Test
    fun hourBoundary_previousHourUidIsChecked() = runTest {
        val h = Harness()
        // Fix captured in the PREVIOUS hour; "now" is just past the boundary.
        val prevHourStart = (nowFixed / HOUR_MS - 1) * HOUR_MS
        val prevUid = uidFor(prevHourStart)
        h.captureResult = fix(prevHourStart + HOUR_MS - 5_000)
        h.pending += prevUid
        h.onDrain = {
            launch {
                h.pending -= prevUid
                h.events.emit(BackendEvent.OutboxEvent.ItemCompleted(driveId, prevUid))
            }
        }

        buildUploader(h, nowMs = prevHourStart + HOUR_MS + 10_000).captureAndUpload(budgetMs = 10_000)

        assertEquals(1, h.drainCalls, "the previous hour's pending row must be found and drained")
    }

    @Test
    fun wrongDriveOrUid_eventsIgnored() = runTest {
        val h = Harness()
        val uid = uidFor(nowFixed - nowFixed % HOUR_MS)
        h.captureResult = fix(nowFixed - 1_000)
        h.pending += uid
        h.onDrain = {
            launch {
                // Noise: same uid wrong drive; same drive wrong uid — must not confirm/exit.
                h.events.emit(BackendEvent.OutboxEvent.ItemCompleted(otherDrive, uid))
                h.events.emit(BackendEvent.OutboxEvent.ItemFailed(driveId, Uuid.random()))
                // Then the genuine one.
                h.pending -= uid
                h.events.emit(BackendEvent.OutboxEvent.ItemCompleted(driveId, uid))
            }
        }

        buildUploader(h).captureAndUpload(budgetMs = 10_000)

        assertFalse(uid in h.pending)
    }
}
