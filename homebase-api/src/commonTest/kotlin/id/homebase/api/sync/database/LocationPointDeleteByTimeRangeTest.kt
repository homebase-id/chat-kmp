package id.homebase.api.sync.database

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coverage for `LocationPoint.deleteByTimeRange` — the local half of the history
 * "delete this day" action: it must remove only the rows in the half-open
 * `[from, to)` range and leave the neighbouring days untouched.
 */
class LocationPointDeleteByTimeRangeTest {

    private val dayMs = 24 * 60 * 60 * 1000L

    private fun point(t: Long) = BufferedLocationPoint(
        t = t, lat = 52.0, lon = 13.0, acc = 10.0, src = "gps", fg = true,
    )

    @Test
    fun deletesOnlyTheTargetDayRows() = runTest {
        DatabaseManager({ createInMemoryDatabase() }).use { dbm ->
            val dayStart = 3 * dayMs // arbitrary middle day
            // Seed one point in the previous day, three across the target day, one in the next day.
            dbm.locationPoint.insertPoints(
                listOf(
                    point(dayStart - 1),              // prev day (just before)
                    point(dayStart),                  // target day start (inclusive)
                    point(dayStart + 12 * 60_000L),   // target day midday
                    point(dayStart + dayMs - 1),      // target day last ms
                    point(dayStart + dayMs),          // next day start (exclusive — must survive)
                ),
            )

            dbm.locationPoint.deleteByTimeRange(dayStart, dayStart + dayMs)

            // Target day: empty.
            assertEquals(0, dbm.locationPoint.selectByTimeRange(dayStart, dayStart + dayMs).size)
            // Neighbours: the prev-day and next-day rows remain.
            assertEquals(1, dbm.locationPoint.selectByTimeRange(dayStart - dayMs, dayStart).size)
            assertEquals(1, dbm.locationPoint.selectByTimeRange(dayStart + dayMs, dayStart + 2 * dayMs).size)
        }
    }
}
