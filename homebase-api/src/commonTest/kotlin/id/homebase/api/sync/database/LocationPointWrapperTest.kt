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
