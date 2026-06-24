package id.homebase.core.location

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.core.location.tracking.DeviceSensors
import id.homebase.core.location.tracking.GpsFixResult
import id.homebase.core.location.tracking.LocationFixRouter
import id.homebase.core.location.tracking.LocationPointStore
import id.homebase.core.location.tracking.LocationTracker
import id.homebase.core.location.tracking.LocationTrackingCoordinator
import id.homebase.core.location.tracking.OneShotLocationProvider
import id.homebase.core.location.tracking.RawLocationPoint
import id.homebase.core.location.tracking.StepSample
import id.homebase.core.location.tracking.TrackingProfile
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Pins [LocationService.getCurrentGps]: fresh-cache short-circuit, permission gate, and that a
 *  fetched fix is routed back through the pipeline (so it's not wasted). */
class LocationServiceTest {

    private val now = 100_000L

    private fun point(t: Long, lon: Double = 13.0) =
        RawLocationPoint(t = t, lat = 52.0, lon = lon, acc = 10.0, src = "gps", fg = true)

    private class NoSensors : DeviceSensors {
        override suspend fun batteryPercent(): Int? = null
        override suspend fun stepsSince(prevPointTimeMs: Long?, lastCumulative: Long?) = StepSample(null, null)
    }

    private object UnavailableTracker : LocationTracker {
        override val isAvailable = false
        override fun start(profile: TrackingProfile) {}
        override fun setProfile(profile: TrackingProfile) {}
        override fun stop() {}
    }

    private class FakeOneShot(val result: GpsFixResult) : OneShotLocationProvider {
        var calls = 0
        override suspend fun getCurrentFix(timeoutMs: Long): GpsFixResult {
            calls++
            return result
        }
    }

    private fun runServiceTest(
        permission: Boolean,
        oneShot: FakeOneShot,
        body: suspend (LocationService, LocationPointStore) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val db = DatabaseManager(
            { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) },
            dispatcher = dispatcher,
            readDispatcher = dispatcher,
        )
        try {
            val preferences = LocationPreferences(db)
            val store = LocationPointStore(db, NoSensors())
            val router = LocationFixRouter(
                store = store,
                allowHistory = { preferences.allowLocationHistory.value },
                persistAsHistory = { store.persistHistory(it) },
                relayLatest = { },
            )
            val coordinator = LocationTrackingCoordinator(preferences, UnavailableTracker, this)
            val service = LocationService(
                coordinator = coordinator,
                router = router,
                pointStore = store,
                preferences = preferences,
                oneShot = oneShot,
                permissionGranted = { permission },
                nowMs = { now },
            )
            body(service, store)
        } finally {
            db.close()
        }
    }

    @Test
    fun returnsCachedFixWhenFresh() {
        val oneShot = FakeOneShot(GpsFixResult.Unavailable)
        runServiceTest(permission = false, oneShot = oneShot) { service, store ->
            store.publishLastKnown(point(t = now)) // age 0 < maxAge
            val result = service.getCurrentGps()
            assertIs<GpsFixResult.Success>(result)
            assertEquals(now, result.point.t)
            assertEquals(0, oneShot.calls) // short-circuited before any fetch
        }
    }

    @Test
    fun returnsPermissionDeniedWhenNotGrantedAndNoFreshCache() {
        val oneShot = FakeOneShot(GpsFixResult.Success(point(t = 50_000)))
        runServiceTest(permission = false, oneShot = oneShot) { service, _ ->
            val result = service.getCurrentGps()
            assertIs<GpsFixResult.PermissionDenied>(result)
            assertEquals(0, oneShot.calls)
        }
    }

    @Test
    fun routesFetchedFixThroughPipeline() {
        val oneShot = FakeOneShot(GpsFixResult.Success(point(t = 50_000, lon = 9.0)))
        runServiceTest(permission = true, oneShot = oneShot) { service, store ->
            val result = service.getCurrentGps()
            assertIs<GpsFixResult.Success>(result)
            assertEquals(1, oneShot.calls)
            // Routed: the fetched fix became the last-known position (router.submit → publishLastKnown).
            assertEquals(50_000, store.lastPoint.value?.t)
            assertEquals(9.0, store.lastPoint.value?.lon)
        }
    }

    @Test
    fun timeoutPassesThroughWithoutRouting() {
        val oneShot = FakeOneShot(GpsFixResult.Timeout)
        runServiceTest(permission = true, oneShot = oneShot) { service, store ->
            val result = service.getCurrentGps()
            assertIs<GpsFixResult.Timeout>(result)
            assertEquals(1, oneShot.calls)
            assertNull(store.lastPoint.value) // nothing routed
        }
    }
}
