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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pins [LocationService.requestLatestGps] / [LocationService.forceCaptureIfTracking]: the
 * fresh-last-known short-circuit, permission gate, single-flight coalescing, the battery
 * min-interval guard, per-reason timing, the consumer gate, and that a fetched fix is routed back
 * through the pipeline (so it's not wasted, and survives the stationary-noise filter).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationServiceTest {

    private val now = 1_000_000L

    private fun point(t: Long, lon: Double = 13.0) =
        RawLocationPoint(t = t, lat = 52.0, lon = lon, acc = 10.0, src = "gps", fg = true)

    private class NoSensors : DeviceSensors {
        override suspend fun batteryPercent(): Int? = null
        override suspend fun stepsSince(prevPointTimeMs: Long?, lastCumulative: Long?) = StepSample(null, null)
        override fun isPowerSaveMode(): Boolean = false
    }

    private open class FakeTracker(override val isAvailable: Boolean) : LocationTracker {
        override fun start(profile: TrackingProfile) {}
        override fun setProfile(profile: TrackingProfile) {}
        override fun stop() {}
    }

    private object UnavailableTracker : FakeTracker(isAvailable = false)
    private object AvailableTracker : FakeTracker(isAvailable = true)

    /** Mutable clock so tests can advance time across the min-interval guard window. */
    private class TestClock(var ms: Long)

    private class FakeOneShot(
        var result: GpsFixResult,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : OneShotLocationProvider {
        var calls = 0
        val timeouts = mutableListOf<Long>()
        val maxAges = mutableListOf<Long>()
        val cacheOnlyCalls = mutableListOf<Boolean>()

        override suspend fun getCurrentFix(timeoutMs: Long, maxAgeMs: Long, cacheOnly: Boolean): GpsFixResult {
            calls++
            timeouts += timeoutMs
            maxAges += maxAgeMs
            cacheOnlyCalls += cacheOnly
            gate?.await() // lets a test hold two callers inside one acquisition (single-flight)
            return result
        }
    }

    private fun runServiceTest(
        permission: Boolean,
        oneShot: FakeOneShot,
        tracker: LocationTracker = UnavailableTracker,
        clock: TestClock = TestClock(now),
        allowHistory: Boolean = false,
        powerSave: Boolean = false,
        body: suspend TestScope.(LocationService, LocationPointStore, TestClock) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val db = DatabaseManager(
            { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) },
            dispatcher = dispatcher,
            readDispatcher = dispatcher,
        )
        try {
            val preferences = LocationPreferences(db)
            if (allowHistory) preferences.setAllowLocationHistory(true)
            val store = LocationPointStore(db, NoSensors())
            val router = LocationFixRouter(
                store = store,
                allowHistory = { preferences.allowLocationHistory.value },
                persistAsHistory = { store.persistHistory(it) },
                relayLatest = { },
            )
            val coordinator = LocationTrackingCoordinator(preferences, tracker, this)
            val service = LocationService(
                coordinator = coordinator,
                router = router,
                pointStore = store,
                preferences = preferences,
                oneShot = oneShot,
                scope = this,
                permissionGranted = { permission },
                nowMs = { clock.ms },
                powerSaveMode = { powerSave },
            )
            this.body(service, store, clock)
        } finally {
            db.close()
        }
    }

    @Test
    fun returnsLastKnownWhenFresh() {
        val oneShot = FakeOneShot(GpsFixResult.Unavailable)
        runServiceTest(permission = false, oneShot = oneShot) { service, store, _ ->
            store.publishLastKnown(point(t = now)) // age 0 < staleAfterMs
            val result = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertIs<GpsFixResult.Success>(result)
            assertEquals(now, result.point.t)
            assertEquals(0, oneShot.calls) // short-circuited before any fetch
        }
    }

    @Test
    fun acquiresWhenLastKnownStale() {
        val oneShot = FakeOneShot(GpsFixResult.Success(point(t = 999_000, lon = 9.0)))
        runServiceTest(permission = true, oneShot = oneShot) { service, store, _ ->
            // staleAfterMs(AppForeground)=60s; seed a point 90s old so it must re-acquire.
            store.publishLastKnown(point(t = now - 90_000))
            val result = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertIs<GpsFixResult.Success>(result)
            assertEquals(1, oneShot.calls)
        }
    }

    @Test
    fun returnsPermissionDeniedWhenNotGrantedAndNoFreshCache() {
        val oneShot = FakeOneShot(GpsFixResult.Success(point(t = 50_000)))
        runServiceTest(permission = false, oneShot = oneShot) { service, _, _ ->
            val result = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertIs<GpsFixResult.PermissionDenied>(result)
            assertEquals(0, oneShot.calls)
        }
    }

    @Test
    fun routesFetchedFixThroughPipeline() {
        val oneShot = FakeOneShot(GpsFixResult.Success(point(t = 990_000, lon = 9.0)))
        runServiceTest(permission = true, oneShot = oneShot) { service, store, _ ->
            val result = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertIs<GpsFixResult.Success>(result)
            assertEquals(1, oneShot.calls)
            // Routed: the fetched fix became the last-known position (router.submit → publishLastKnown).
            assertEquals(990_000, store.lastPoint.value?.t)
            assertEquals(9.0, store.lastPoint.value?.lon)
        }
    }

    @Test
    fun timeoutPassesThroughWithoutRouting() {
        val oneShot = FakeOneShot(GpsFixResult.Timeout)
        runServiceTest(permission = true, oneShot = oneShot) { service, store, _ ->
            val result = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertIs<GpsFixResult.Timeout>(result)
            assertEquals(1, oneShot.calls)
            assertNull(store.lastPoint.value) // nothing routed
        }
    }

    @Test
    fun concurrentRequestsCoalesceToOneAcquisition() {
        val gate = CompletableDeferred<Unit>()
        val oneShot = FakeOneShot(GpsFixResult.Success(point(t = 990_000)), gate = gate)
        runServiceTest(permission = true, oneShot = oneShot) { service, _, _ ->
            // Both callers see a stale (absent) last-known and race into requestLatestGps.
            val a = async { service.requestLatestGps(GpsRequestReason.AppForeground) }
            val b = async { service.requestLatestGps(GpsRequestReason.AppForeground) }
            advanceUntilIdle() // both reach await(); the single acquisition is parked on the gate
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, oneShot.calls) // single-flight: only one real acquisition
            val ra = a.await()
            val rb = b.await()
            assertIs<GpsFixResult.Success>(ra)
            assertIs<GpsFixResult.Success>(rb)
            assertEquals(990_000, ra.point.t)
            assertEquals(990_000, rb.point.t)
        }
    }

    @Test
    fun batteryGuardReusesLastKnownWithinMinInterval() {
        // First acquisition fails (Timeout) so it leaves a recent lastAcquireStartMs but does NOT
        // refresh last-known; the seeded stale point stays the best we have.
        val oneShot = FakeOneShot(GpsFixResult.Timeout)
        val clock = TestClock(now)
        runServiceTest(permission = true, oneShot = oneShot, clock = clock) { service, store, c ->
            val stale = point(t = now - 200_000, lon = 7.0) // older than every staleAfterMs
            store.publishLastKnown(stale)

            val first = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertIs<GpsFixResult.Timeout>(first)
            assertEquals(1, oneShot.calls)

            // 30s later — still within MIN_ACQUISITION_INTERVAL_MS (60s) — must NOT re-acquire.
            c.ms = now + 30_000
            val second = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertEquals(1, oneShot.calls) // battery guard skipped the second acquisition
            assertIs<GpsFixResult.Success>(second)
            assertEquals(stale.t, second.point.t) // reused last-known within tolerance
        }
    }

    @Test
    fun acquiresAgainAfterMinIntervalElapses() {
        val oneShot = FakeOneShot(GpsFixResult.Timeout)
        val clock = TestClock(now)
        runServiceTest(permission = true, oneShot = oneShot, clock = clock) { service, store, c ->
            store.publishLastKnown(point(t = now - 200_000))
            service.requestLatestGps(GpsRequestReason.AppForeground)
            assertEquals(1, oneShot.calls)

            // Past the 60s min-interval — a fresh acquisition is allowed.
            c.ms = now + LocationService.MIN_ACQUISITION_INTERVAL_MS + 1
            service.requestLatestGps(GpsRequestReason.AppForeground)
            assertEquals(2, oneShot.calls)
        }
    }

    @Test
    fun foregroundReasonUsesLongTimeout() {
        val oneShot = FakeOneShot(GpsFixResult.Timeout)
        runServiceTest(permission = true, oneShot = oneShot) { service, _, _ ->
            service.requestLatestGps(GpsRequestReason.AppForeground)
            assertEquals(15_000L, oneShot.timeouts.single())
        }
    }

    @Test
    fun pushReasonUsesShortTimeout() {
        val oneShot = FakeOneShot(GpsFixResult.Timeout)
        runServiceTest(permission = true, oneShot = oneShot) { service, _, _ ->
            service.requestLatestGps(GpsRequestReason.PushReceived)
            assertEquals(5_000L, oneShot.timeouts.single())
        }
    }

    @Test
    fun passesReasonStalenessAsCacheMaxAge() {
        // The provider is told to accept an OS last-known cache only within the reason's staleness
        // tolerance, otherwise power the radio (#886 fix 1).
        val oneShot = FakeOneShot(GpsFixResult.Timeout)
        runServiceTest(permission = true, oneShot = oneShot) { service, _, _ ->
            service.requestLatestGps(GpsRequestReason.PushReceived)
            assertEquals(GpsRequestReason.PushReceived.staleAfterMs, oneShot.maxAges.single())
        }
    }

    @Test
    fun batterySaverAcquiresCacheOnly() {
        // Power save on → acquisition is cache-only (no radio).
        val oneShot = FakeOneShot(GpsFixResult.Success(point(t = 990_000)))
        runServiceTest(permission = true, oneShot = oneShot, powerSave = true) { service, _, _ ->
            service.requestLatestGps(GpsRequestReason.AppForeground)
            assertEquals(1, oneShot.calls)
            assertEquals(true, oneShot.cacheOnlyCalls.single())
        }
    }

    @Test
    fun nonSaverAllowsRadio() {
        val oneShot = FakeOneShot(GpsFixResult.Timeout)
        runServiceTest(permission = true, oneShot = oneShot, powerSave = false) { service, _, _ ->
            service.requestLatestGps(GpsRequestReason.AppForeground)
            assertEquals(false, oneShot.cacheOnlyCalls.single())
        }
    }

    @Test
    fun forceCaptureSkippedWhenNothingTracking() {
        val oneShot = FakeOneShot(GpsFixResult.Success(point(t = 990_000)))
        // UnavailableTracker + no history → isCaptureWanted() is false.
        runServiceTest(permission = true, oneShot = oneShot, tracker = UnavailableTracker) { service, _, _ ->
            val result = service.forceCaptureIfTracking(GpsRequestReason.PushReceived)
            assertNull(result)
            assertEquals(0, oneShot.calls)
        }
    }

    @Test
    fun forceCaptureProceedsWhenHistoryOn() {
        val oneShot = FakeOneShot(GpsFixResult.Success(point(t = 990_000, lon = 5.0)))
        // AvailableTracker + history on + (jvm) permission granted → isCaptureWanted() is true.
        runServiceTest(
            permission = true,
            oneShot = oneShot,
            tracker = AvailableTracker,
            allowHistory = true,
        ) { service, store, _ ->
            val result = service.forceCaptureIfTracking(GpsRequestReason.PushReceived)
            assertIs<GpsFixResult.Success>(result)
            assertEquals(1, oneShot.calls)
            assertEquals(990_000, store.lastPoint.value?.t) // routed + persisted (history on)
        }
    }
}
