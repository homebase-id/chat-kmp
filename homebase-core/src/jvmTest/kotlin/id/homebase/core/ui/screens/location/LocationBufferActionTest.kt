package id.homebase.core.ui.screens.location

import id.homebase.api.client.eventbus.BackendEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Locks the outbox-event → buffer-action mapping for the location uploader.
 *
 * Regression: `OutboxItemDropped` (permanent drop — retries exhausted or a
 * permanent failure like "AES key must match") had NO handler, so the buffered
 * points for that hour stayed flush-marked forever: never re-flushed, never
 * deleted, and the pending count silently wrong. The drop path emits ONLY
 * `OutboxItemDropped` — no `ItemFailed` — so the retry-unmark must happen on
 * the drop event too. The next flush of that hour rebuilds the request from
 * live state (fresh key, current points) via replaceEnqueue.
 */
class LocationBufferActionTest {

    private val locationDrive = Uuid.random()
    private val otherDrive = Uuid.random()
    private val uid = Uuid.random()

    @Test
    fun itemCompletedDrainsFlushedRows() {
        assertEquals(
            BufferAction.DrainFlushed,
            bufferActionFor(BackendEvent.OutboxEvent.ItemCompleted(locationDrive, uid), locationDrive),
        )
    }

    @Test
    fun itemFailedUnmarksForRetry() {
        assertEquals(
            BufferAction.UnmarkForRetry,
            bufferActionFor(BackendEvent.OutboxEvent.ItemFailed(locationDrive, uid), locationDrive),
        )
    }

    @Test
    fun outboxItemDroppedUnmarksForRetry() {
        assertEquals(
            BufferAction.UnmarkForRetry,
            bufferActionFor(
                BackendEvent.OutboxEvent.OutboxItemDropped(
                    locationDrive, uid, attempts = 20, reason = "retries exhausted (20)",
                ),
                locationDrive,
            ),
        )
    }

    @Test
    fun otherDrivesEventsAreIgnored() {
        assertEquals(
            BufferAction.None,
            bufferActionFor(BackendEvent.OutboxEvent.ItemCompleted(otherDrive, uid), locationDrive),
        )
        assertEquals(
            BufferAction.None,
            bufferActionFor(BackendEvent.OutboxEvent.ItemFailed(otherDrive, uid), locationDrive),
        )
        assertEquals(
            BufferAction.None,
            bufferActionFor(
                BackendEvent.OutboxEvent.OutboxItemDropped(otherDrive, uid, attempts = 1),
                locationDrive,
            ),
        )
    }

    @Test
    fun unrelatedEventsAreIgnored() {
        assertEquals(
            BufferAction.None,
            bufferActionFor(BackendEvent.OutboxEvent.ItemEnqueued(locationDrive, uid), locationDrive),
        )
        assertEquals(
            BufferAction.None,
            bufferActionFor(BackendEvent.OutboxEvent.Started, locationDrive),
        )
    }
}
