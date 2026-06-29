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
 * min-interval guard, per-reason timing, the consumer gate, and routing back through the pipeline.
 *
 * The one-shot fake supplies the two platform primitives ([OneShotLocationProvider.lastKnownFix] /
 * [OneShotLocationProvider.acquireFreshFix]); the cache-vs-radio orchestration is the real common
 * [OneShotLocationProvider.getCurrentFix], so these tests also exercise the cache/age/cacheOnly path.
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

    /**
     * Supplies the two platform primitives. [cached] is the OS last-known; [fresh] is what a radio
     * acquisition yields. Counters let tests assert whether the radio was actually used.
     */
    private class FakeOneShot(
        var fresh: GpsFixResult = GpsFixResult.Timeout,
        var cached: RawLocationPoint? = null,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : OneShotLocationProvider {
        var freshCalls = 0
        var lastKnownCalls = 0
        val freshTimeouts = mutableListOf<Long>()

        override suspend fun lastKnownFix(): RawLocationPoint? {
            lastKnownCalls++
            return cached
        }

        override suspend fun acquireFreshFix(timeoutMs: Long): GpsFixResult {
            freshCalls++
            freshTimeouts += timeoutMs
            gate?.await() // lets a test hold two callers inside one acquisition (single-flight)
            return fresh
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
        val oneShot = FakeOneShot()
        runServiceTest(permission = false, oneShot = oneShot) { service, store, _ ->
            store.publishLastKnown(point(t = now)) // age 0 < staleAfterMs
            val result = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertIs<GpsFixResult.Success>(result)
            assertEquals(now, result.point.t)
            assertEquals(0, oneShot.freshCalls) // short-circuited before any provider call
            assertEquals(0, oneShot.lastKnownCalls)
        }
    }

    @Test
    fun acquiresWhenLastKnownStale() {
        val oneShot = FakeOneShot(fresh = GpsFixResult.Success(point(t = 999_000, lon = 9.0)))
        runServiceTest(permission = true, oneShot = oneShot) { service, store, _ ->
            // staleAfterMs(AppForeground)=60s; seed a point 90s old so it must re-acquire.
            store.publishLastKnown(point(t = now - 90_000))
            val result = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertIs<GpsFixResult.Success>(result)
            assertEquals(1, oneShot.freshCalls)
        }
    }

    @Test
    fun returnsPermissionDeniedWhenNotGrantedAndNoFreshCache() {
        val oneShot = FakeOneShot(fresh = GpsFixResult.Success(point(t = 50_000)))
        runServiceTest(permission = false, oneShot = oneShot) { service, _, _ ->
            val result = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertIs<GpsFixResult.PermissionDenied>(result)
            assertEquals(0, oneShot.freshCalls)
            assertEquals(0, oneShot.lastKnownCalls)
        }
    }

    @Test
    fun routesFetchedFixThroughPipeline() {
        val oneShot = FakeOneShot(fresh = GpsFixResult.Success(point(t = 990_000, lon = 9.0)))
        runServiceTest(permission = true, oneShot = oneShot) { service, store, _ ->
            val result = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertIs<GpsFixResult.Success>(result)
            assertEquals(1, oneShot.freshCalls)
            // Routed: the fetched fix became the last-known position (router.submit → publishLastKnown).
            assertEquals(990_000, store.lastPoint.value?.t)
            assertEquals(9.0, store.lastPoint.value?.lon)
        }
    }

    @Test
    fun timeoutPassesThroughWithoutRouting() {
        val oneShot = FakeOneShot(fresh = GpsFixResult.Timeout)
        runServiceTest(permission = true, oneShot = oneShot) { service, store, _ ->
            val result = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertIs<GpsFixResult.Timeout>(result)
            assertEquals(1, oneShot.freshCalls)
            assertNull(store.lastPoint.value) // nothing routed
        }
    }

    @Test
    fun concurrentRequestsCoalesceToOneAcquisition() {
        val gate = CompletableDeferred<Unit>()
        val oneShot = FakeOneShot(fresh = GpsFixResult.Success(point(t = 990_000)), gate = gate)
        runServiceTest(permission = true, oneShot = oneShot) { service, _, _ ->
            // Both callers see a stale (absent) last-known and race into requestLatestGps.
            val a = async { service.requestLatestGps(GpsRequestReason.AppForeground) }
            val b = async { service.requestLatestGps(GpsRequestReason.AppForeground) }
            advanceUntilIdle() // both reach await(); the single acquisition is parked on the gate
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, oneShot.freshCalls) // single-flight: only one real acquisition
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
        val oneShot = FakeOneShot(fresh = GpsFixResult.Timeout)
        val clock = TestClock(now)
        runServiceTest(permission = true, oneShot = oneShot, clock = clock) { service, store, c ->
            val stale = point(t = now - 200_000, lon = 7.0) // older than every staleAfterMs
            store.publishLastKnown(stale)

            val first = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertIs<GpsFixResult.Timeout>(first)
            assertEquals(1, oneShot.freshCalls)

            // 30s later — still within MIN_ACQUISITION_INTERVAL_MS (60s) — must NOT re-acquire.
            c.ms = now + 30_000
            val second = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertEquals(1, oneShot.freshCalls) // battery guard skipped the second acquisition
            assertIs<GpsFixResult.Success>(second)
            assertEquals(stale.t, second.point.t) // reused last-known within tolerance
        }
    }

    @Test
    fun acquiresAgainAfterMinIntervalElapses() {
        val oneShot = FakeOneShot(fresh = GpsFixResult.Timeout)
        val clock = TestClock(now)
        runServiceTest(permission = true, oneShot = oneShot, clock = clock) { service, store, c ->
            store.publishLastKnown(point(t = now - 200_000))
            service.requestLatestGps(GpsRequestReason.AppForeground)
            assertEquals(1, oneShot.freshCalls)

            // Past the 60s min-interval — a fresh acquisition is allowed.
            c.ms = now + LocationService.MIN_ACQUISITION_INTERVAL_MS + 1
            service.requestLatestGps(GpsRequestReason.AppForeground)
            assertEquals(2, oneShot.freshCalls)
        }
    }

    @Test
    fun foregroundReasonUsesLongTimeout() {
        val oneShot = FakeOneShot(fresh = GpsFixResult.Timeout)
        runServiceTest(permission = true, oneShot = oneShot) { service, _, _ ->
            service.requestLatestGps(GpsRequestReason.AppForeground)
            assertEquals(15_000L, oneShot.freshTimeouts.single())
        }
    }

    @Test
    fun pushReasonUsesShortTimeout() {
        val oneShot = FakeOneShot(fresh = GpsFixResult.Timeout)
        runServiceTest(permission = true, oneShot = oneShot) { service, _, _ ->
            service.requestLatestGps(GpsRequestReason.PushReceived)
            assertEquals(5_000L, oneShot.freshTimeouts.single())
        }
    }

    @Test
    fun servesOsCacheWithinMaxAge() {
        // App store empty so LocationService proceeds; an OS last-known fresher than the reason's
        // staleness tolerance is served without powering the radio (#886 fix 1).
        val oneShot = FakeOneShot(
            fresh = GpsFixResult.Success(point(t = 1, lon = 1.0)),
            cached = point(t = now - 10_000, lon = 7.0), // 10s old, within AppForeground's 60s
        )
        runServiceTest(permission = true, oneShot = oneShot) { service, _, _ ->
            val result = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertIs<GpsFixResult.Success>(result)
            assertEquals(now - 10_000, result.point.t) // the cached one, not the fresh one
            assertEquals(0, oneShot.freshCalls) // no radio
        }
    }

    @Test
    fun acquiresWhenOsCacheTooOld() {
        val oneShot = FakeOneShot(
            fresh = GpsFixResult.Success(point(t = 990_000, lon = 9.0)),
            cached = point(t = now - 90_000), // 90s old > AppForeground's 60s
        )
        runServiceTest(permission = true, oneShot = oneShot) { service, _, _ ->
            val result = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertIs<GpsFixResult.Success>(result)
            assertEquals(990_000, result.point.t) // the freshly acquired one
            assertEquals(1, oneShot.freshCalls)
        }
    }

    @Test
    fun batterySaverServesCacheOnly() {
        // Power save on → cache-only: serve the OS last-known at ANY age, never power the radio.
        val oneShot = FakeOneShot(
            fresh = GpsFixResult.Success(point(t = 990_000)),
            cached = point(t = now - 200_000, lon = 7.0), // very old
        )
        runServiceTest(permission = true, oneShot = oneShot, powerSave = true) { service, _, _ ->
            val result = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertIs<GpsFixResult.Success>(result)
            assertEquals(now - 200_000, result.point.t) // old cache still served
            assertEquals(0, oneShot.freshCalls) // never the radio
        }
    }

    @Test
    fun batterySaverWithoutCacheIsUnavailable() {
        val oneShot = FakeOneShot(fresh = GpsFixResult.Success(point(t = 990_000)), cached = null)
        runServiceTest(permission = true, oneShot = oneShot, powerSave = true) { service, _, _ ->
            val result = service.requestLatestGps(GpsRequestReason.AppForeground)
            assertIs<GpsFixResult.Unavailable>(result)
            assertEquals(0, oneShot.freshCalls)
        }
    }

    @Test
    fun nonSaverAllowsRadio() {
        val oneShot = FakeOneShot(fresh = GpsFixResult.Timeout, cached = null)
        runServiceTest(permission = true, oneShot = oneShot, powerSave = false) { service, _, _ ->
            service.requestLatestGps(GpsRequestReason.AppForeground)
            assertEquals(1, oneShot.freshCalls)
        }
    }

    @Test
    fun forceCaptureSkippedWhenNothingTracking() {
        val oneShot = FakeOneShot(fresh = GpsFixResult.Success(point(t = 990_000)))
        // UnavailableTracker + no history → isCaptureWanted() is false.
        runServiceTest(permission = true, oneShot = oneShot, tracker = UnavailableTracker) { service, _, _ ->
            val result = service.forceCaptureIfTracking(GpsRequestReason.PushReceived)
            assertNull(result)
            assertEquals(0, oneShot.freshCalls)
            assertEquals(0, oneShot.lastKnownCalls)
        }
    }

    @Test
    fun forceCaptureProceedsWhenHistoryOn() {
        val oneShot = FakeOneShot(fresh = GpsFixResult.Success(point(t = 990_000, lon = 5.0)))
        // AvailableTracker + history on + (jvm) permission granted → isCaptureWanted() is true.
        runServiceTest(
            permission = true,
            oneShot = oneShot,
            tracker = AvailableTracker,
            allowHistory = true,
        ) { service, store, _ ->
            val result = service.forceCaptureIfTracking(GpsRequestReason.PushReceived)
            assertIs<GpsFixResult.Success>(result)
            assertEquals(1, oneShot.freshCalls)
            assertEquals(990_000, store.lastPoint.value?.t) // routed + persisted (history on)
        }
    }
}
