package id.homebase.core.location.tracking

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.sync.database.DatabaseManager
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun dropsStationaryNoiseWithinTimeAndDistanceWindow() = runStoreTest { store, _ ->
        // ~5.5 m east of the first point (well under 8 m), 10 s later (under 20 s).
        store.submit(
            listOf(
                point(t = 0),
                point(t = 10_000, lon = 13.00008),
            )
        )
        assertEquals(1, store.countPendingUpload())
    }

    @Test
    fun keepsPointThatMovedFarEnough() = runStoreTest { store, _ ->
        // ~70 m east, 10 s later — distance clears the gate even though time doesn't.
        store.submit(
            listOf(
                point(t = 0),
                point(t = 10_000, lon = 13.001),
            )
        )
        assertEquals(2, store.countPendingUpload())
    }

    @Test
    fun keepsStationaryPointAfterTimeWindow() = runStoreTest { store, _ ->
        store.submit(
            listOf(
                point(t = 0),
                point(t = 25_000),
            )
        )
        assertEquals(2, store.countPendingUpload())
    }

    @Test
    fun dropsOutOfOrderAndDuplicateTimestamps() = runStoreTest { store, _ ->
        store.submit(listOf(point(t = 60_000, lon = 14.0)))
        store.submit(listOf(point(t = 30_000), point(t = 60_000)))
        assertEquals(1, store.countPendingUpload())
        assertEquals(60_000, assertNotNull(store.lastPoint.value).t)
    }

    @Test
    fun dedupsAgainstDbAfterColdStart() = runStoreTest { store, db ->
        store.submit(listOf(point(t = 0)))
        // Fresh store instance — in-memory lastPoint empty, must seed from DB.
        val coldStore = LocationPointStore(db, FakeSensors())
        coldStore.submit(listOf(point(t = 5_000)))
        assertEquals(1, coldStore.countPendingUpload())
    }

    @Test
    fun stampsBatteryOnEveryPointAndStepDeltaOnNewest() = runStoreTest(
        sensors = FakeSensors(battery = 80, delta = 42, cumulative = 1000L),
    ) { store, db ->
        // Two far-apart points accepted in one submit.
        store.submit(listOf(point(t = 0), point(t = 60_000, lon = 13.01)))
        val rows = db.locationPoint.selectByTimeRange(0, 3_600_000)
        assertEquals(2, rows.size)
        // Battery on both.
        assertEquals(80, rows[0].bat)
        assertEquals(80, rows[1].bat)
        // Step delta only on the newest (the window since the previous point).
        assertNull(rows[0].steps)
        assertEquals(42, rows[1].steps)
    }

    @Test
    fun nullSensorsLeaveStepsAndBatteryNull() = runStoreTest { store, db ->
        store.submit(listOf(point(t = 0)))
        val row = db.locationPoint.selectByTimeRange(0, 3_600_000).single()
        assertNull(row.steps)
        assertNull(row.bat)
    }

    @Test
    fun firesFlushTriggerWhenPointsAreBuffered() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val db = DatabaseManager(
            { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) },
            dispatcher = dispatcher,
            readDispatcher = dispatcher,
        )
        try {
            var triggers = 0
            val store = LocationPointStore(db, FakeSensors(), onPointsBuffered = { triggers++ })
            // First batch accepts a point → one trigger.
            store.submit(listOf(point(t = 0)))
            assertEquals(1, triggers)
            // A stationary-noise-only batch buffers nothing → no trigger (the
            // common flush trigger is the iOS background-flush fix; it must not
            // fire on dropped fixes).
            store.submit(listOf(point(t = 5_000, lon = 13.00008)))
            assertEquals(1, triggers)
            // A far-enough point is accepted → another trigger.
            store.submit(listOf(point(t = 25_000, lon = 13.001)))
            assertEquals(2, triggers)
        } finally {
            db.close()
        }
    }

    @Test
    fun haversineSanity() {
        // One degree of longitude at the equator ≈ 111.3 km.
        val d = LocationPointStore.haversineMeters(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_320.0, d, 200.0)
    }
}
