package id.homebase.api.sync.database

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

/**
 * Drain semantics of the Location buffer: mark-on-enqueue → delete-on-complete,
 * mark → clear-on-failure → retry visibility. See LocationTrackUploaderService.
 */
class LocationPointWrapperTest {

    private val hourMs = 3_600_000L

    private fun point(t: Long, lat: Double = 52.0, lon: Double = 13.0) = BufferedLocationPoint(
        t = t, lat = lat, lon = lon, acc = 10.0, src = "fused", fg = false,
    )

    private fun runDbTest(body: suspend TestScope.(LocationPointWrapper) -> Unit) = runTest {
        val dbDispatcher = StandardTestDispatcher(testScheduler)
        val db = DatabaseManager(
            { createInMemoryDatabase() },
            dispatcher = dbDispatcher,
            readDispatcher = dbDispatcher,
        )
        try {
            body(db.locationPoint)
        } finally {
            db.close()
        }
    }

    @Test
    fun insertIsIdempotentOnTimestamp() = runDbTest { buffer ->
        buffer.insertPoints(listOf(point(1000), point(1000), point(2000)))
        assertEquals(2, buffer.countAll())
    }

    @Test
    fun pendingHoursOnlyCountsUnmarkedRows() = runDbTest { buffer ->
        val h0 = 0L
        val h1 = hourMs
        buffer.insertPoints(listOf(point(h0 + 1), point(h1 + 1)))
        assertEquals(listOf(0L, 1L), buffer.selectPendingHours())

        val uid = Uuid.random()
        buffer.markFlushed(uid, h0, h1)
        assertEquals(listOf(1L), buffer.selectPendingHours())
    }

    @Test
    fun completeDrainsOnlyTheMarkedHour() = runDbTest { buffer ->
        val h0 = 0L
        val h1 = hourMs
        buffer.insertPoints(listOf(point(h0 + 1), point(h0 + 2), point(h1 + 1)))
        val uid = Uuid.random()
        buffer.markFlushed(uid, h0, h1)

        buffer.deleteByFlushUid(uid)
        assertEquals(1, buffer.countAll())
        assertEquals(listOf(1L), buffer.selectPendingHours())
    }

    @Test
    fun failureUnmarksRowsForRetry() = runDbTest { buffer ->
        val h0 = 0L
        buffer.insertPoints(listOf(point(h0 + 1)))
        val uid = Uuid.random()
        buffer.markFlushed(uid, h0, hourMs)
        assertEquals(emptyList(), buffer.selectPendingHours())

        buffer.clearFlushMark(uid)
        assertEquals(listOf(0L), buffer.selectPendingHours())
        assertEquals(1, buffer.countAll())
    }

    @Test
    fun reFlushOfHourIncludesMarkedAndUnmarkedRows() = runDbTest { buffer ->
        val h0 = 0L
        buffer.insertPoints(listOf(point(h0 + 1)))
        buffer.markFlushed(Uuid.random(), h0, hourMs)
        // A new point lands in the same hour while the first flush is pending.
        buffer.insertPoints(listOf(point(h0 + 2)))

        assertEquals(listOf(0L), buffer.selectPendingHours())
        assertEquals(2, buffer.selectByTimeRange(h0, hourMs).size)
    }

    @Test
    fun countUnmarkedSinceExcludesEnqueuedRows() = runDbTest { buffer ->
        val h0 = 0L
        buffer.insertPoints(listOf(point(h0 + 1), point(h0 + 2)))
        buffer.markFlushed(Uuid.random(), h0, hourMs)
        // New point after the flush — the only one not yet in a file.
        buffer.insertPoints(listOf(point(h0 + 3)))

        assertEquals(1, buffer.countUnmarkedSince(0))
        assertEquals(3, buffer.countSince(0))
    }

    @Test
    fun countUnmarkedExcludesEnqueuedRows() = runDbTest { buffer ->
        buffer.insertPoints(listOf(point(1), point(2), point(hourMs + 1)))
        assertEquals(3, buffer.countUnmarked())
        // Mark hour 0's two rows as flushed; only hour 1's row stays unmarked.
        buffer.markFlushed(Uuid.random(), 0, hourMs)
        assertEquals(1, buffer.countUnmarked())
        assertEquals(3, buffer.countAll())
    }

    @Test
    fun selectHoursWithRowsReturnsMarkedAndUnmarkedHours() = runDbTest { buffer ->
        buffer.insertPoints(listOf(point(1), point(hourMs + 1), point(2 * hourMs + 1)))
        // Mark hour 0 — it must STILL appear (rows are kept until the hour closes).
        buffer.markFlushed(Uuid.random(), 0, hourMs)
        assertEquals(listOf(0L, 1L, 2L), buffer.selectHoursWithRows())
        // selectPendingHours, by contrast, drops the marked hour.
        assertEquals(listOf(1L, 2L), buffer.selectPendingHours())
    }

    @Test
    fun drainKeepsRowsWhileHourOpenAndDeletesOnceClosed() = runDbTest { buffer ->
        // Hour 0 holds two points; flush marks them under uid.
        val uid = Uuid.random()
        buffer.insertPoints(listOf(point(1), point(2)))
        buffer.markFlushed(uid, 0, hourMs)

        // Confirmation arrives while hour 0 is still the current hour (now within
        // hour 0): rows must be KEPT so the next flush re-serializes the full hour.
        buffer.deleteFlushedIfHourClosed(uid, nowMs = 1_000)
        assertEquals(2, buffer.countAll())
        // The complete hour is still re-serializable (marked rows included).
        assertEquals(2, buffer.selectByTimeRange(0, hourMs).size)

        // Confirmation after the hour has closed (now in hour 1): rows drained.
        buffer.deleteFlushedIfHourClosed(uid, nowMs = hourMs + 1)
        assertEquals(0, buffer.countAll())
    }

    @Test
    fun drainOnlyTouchesTheConfirmedUidsHour() = runDbTest { buffer ->
        // Two closed hours, each flushed under its own uid.
        val uid0 = Uuid.random()
        val uid1 = Uuid.random()
        buffer.insertPoints(listOf(point(1), point(hourMs + 1)))
        buffer.markFlushed(uid0, 0, hourMs)
        buffer.markFlushed(uid1, hourMs, 2 * hourMs)

        // Now is well past both hours, but only hour 0's confirmation arrives.
        buffer.deleteFlushedIfHourClosed(uid0, nowMs = 5 * hourMs)
        assertEquals(listOf(1L), buffer.selectHoursWithRows())
        assertEquals(1, buffer.countAll())
    }

    @Test
    fun deleteByTimeRangeRemovesOnlyTheTargetDay() = runDbTest { buffer ->
        val dayMs = 24 * hourMs
        val dayStart = 3 * dayMs // arbitrary middle day
        // One point just before the day, three across it, one at the next day's start.
        buffer.insertPoints(
            listOf(
                point(dayStart - 1),            // prev day
                point(dayStart),                // day start (inclusive)
                point(dayStart + 12 * hourMs),  // midday
                point(dayStart + dayMs - 1),    // last ms of the day
                point(dayStart + dayMs),        // next day start (exclusive — must survive)
            ),
        )

        buffer.deleteByTimeRange(dayStart, dayStart + dayMs)

        // Target day emptied, both neighbours untouched.
        assertEquals(0, buffer.selectByTimeRange(dayStart, dayStart + dayMs).size)
        assertEquals(1, buffer.selectByTimeRange(dayStart - dayMs, dayStart).size)
        assertEquals(1, buffer.selectByTimeRange(dayStart + dayMs, dayStart + 2 * dayMs).size)
    }

    @Test
    fun countSinceAndLatestAndRetention() = runDbTest { buffer ->
        buffer.insertPoints(listOf(point(1000), point(5000), point(9000)))
        assertEquals(2, buffer.countSince(5000))
        assertEquals(9000, assertNotNull(buffer.selectLatest()).t)

        buffer.deleteOlderThan(5000)
        assertEquals(2, buffer.countAll())

        buffer.deleteAll()
        assertEquals(0, buffer.countAll())
        assertNull(buffer.selectLatest())
    }
}
