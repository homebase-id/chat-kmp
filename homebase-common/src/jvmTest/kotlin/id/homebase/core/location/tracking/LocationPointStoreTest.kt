package id.homebase.core.location.tracking

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sensors stub: fixed battery + step delta, so the store's stamping is testable. */
private class FakeSensors(
    private val battery: Int? = null,
    private val delta: Int? = null,
    private val cumulative: Long? = null,
) : DeviceSensors {
    override suspend fun batteryPercent(): Int? = battery
    override suspend fun stepsSince(prevPointTimeMs: Long?, lastCumulative: Long?): StepSample =
        StepSample(delta, cumulative)
}

/**
 * Pins the thin-store primitives [LocationPointStore.dedup] (out-of-order + stationary-noise filter)
 * and [LocationPointStore.persistHistory] (device-reading stamping). The persist-vs-relay routing is
 * tested in [LocationFixRouterTest].
 */
class LocationPointStoreTest {

    private fun point(
        t: Long,
        lat: Double = 52.0,
        lon: Double = 13.0,
        src: String = "gps",
    ) = RawLocationPoint(t = t, lat = lat, lon = lon, acc = 10.0, src = src, fg = true)

    private fun runStoreTest(
        sensors: DeviceSensors = FakeSensors(),
        body: suspend (LocationPointStore, DatabaseManager) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val db = DatabaseManager(
            { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) },
            dispatcher = dispatcher,
            readDispatcher = dispatcher,
        )
        try {
            body(LocationPointStore(db, sensors), db)
        } finally {
            db.close()
        }
    }

    @Test
    fun dedupDropsStationaryNoiseWithinTimeAndDistanceWindow() = runStoreTest { store, _ ->
        // ~5.5 m east of the first point (well under 8 m), 10 s later (under 20 s).
        val accepted = store.dedup(listOf(point(t = 0), point(t = 10_000, lon = 13.00008)))
        assertEquals(1, accepted.size)
    }

    @Test
    fun dedupKeepsPointThatMovedFarEnough() = runStoreTest { store, _ ->
        // ~70 m east, 10 s later — distance clears the gate even though time doesn't.
        val accepted = store.dedup(listOf(point(t = 0), point(t = 10_000, lon = 13.001)))
        assertEquals(2, accepted.size)
    }

    @Test
    fun dedupKeepsStationaryPointAfterTimeWindow() = runStoreTest { store, _ ->
        val accepted = store.dedup(listOf(point(t = 0), point(t = 25_000)))
        assertEquals(2, accepted.size)
    }

    @Test
    fun dedupDropsPointsOlderOrEqualToLastKnown() = runStoreTest { store, _ ->
        store.publishLastKnown(point(t = 60_000, lon = 14.0))
        // Both are <= the last-known time, so both are dropped (the sort doesn't rescue them).
        val accepted = store.dedup(listOf(point(t = 30_000), point(t = 60_000)))
        assertTrue(accepted.isEmpty())
    }

    @Test
    fun dedupUsesDbBaselineAfterColdStart() = runStoreTest { store, db ->
        store.persistHistory(listOf(point(t = 0)))
        // Fresh store instance — in-memory lastPoint empty, must seed the dedup baseline from the DB.
        val coldStore = LocationPointStore(db, FakeSensors())
        // 5 s later, same spot → stationary noise relative to the persisted point → dropped.
        val accepted = coldStore.dedup(listOf(point(t = 5_000)))
        assertTrue(accepted.isEmpty())
    }

    @Test
    fun publishLastKnownUpdatesLatest() = runStoreTest { store, _ ->
        store.publishLastKnown(point(t = 42_000, lon = 9.0))
        assertEquals(42_000, store.lastPoint.value?.t)
        assertEquals(9.0, store.lastPoint.value?.lon)
    }

    @Test
    fun persistHistoryStampsBatteryOnEveryPointAndStepDeltaOnNewest() = runStoreTest(
        sensors = FakeSensors(battery = 80, delta = 42, cumulative = 1000L),
    ) { store, db ->
        store.persistHistory(listOf(point(t = 0), point(t = 60_000, lon = 13.01)))
        val rows = db.locationPoint.selectByTimeRange(0, 3_600_000)
        assertEquals(2, rows.size)
        assertEquals(80, rows[0].bat)
        assertEquals(80, rows[1].bat)
        // Step delta only on the newest (the window since the previous point).
        assertNull(rows[0].steps)
        assertEquals(42, rows[1].steps)
    }

    @Test
    fun persistHistoryNullSensorsLeaveStepsAndBatteryNull() = runStoreTest { store, db ->
        store.persistHistory(listOf(point(t = 0)))
        val row = db.locationPoint.selectByTimeRange(0, 3_600_000).single()
        assertNull(row.steps)
        assertNull(row.bat)
    }

    @Test
    fun haversineSanity() {
        // One degree of longitude at the equator ≈ 111.3 km.
        val d = LocationPointStore.haversineMeters(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_320.0, d, 200.0)
    }
}
