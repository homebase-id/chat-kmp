package id.homebase.core.location

import id.homebase.core.location.tracking.DemandReason
import id.homebase.core.location.tracking.DemandToken
import id.homebase.core.location.tracking.GpsFixResult
import id.homebase.core.location.tracking.LocationFixRouter
import id.homebase.core.location.tracking.LocationPointStore
import id.homebase.core.location.tracking.LocationTrackingCoordinator
import id.homebase.core.location.tracking.OneShotLocationProvider
import id.homebase.core.location.tracking.RawLocationPoint
import co.touchlab.kermit.Logger
import id.homebase.core.permissions.isLocationPermissionGranted
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock

/**
 * The single public entry point for "this device's location", callable from anywhere in the app. It
 * composes the three otherwise-separate concerns so callers don't reach into them individually:
 *  - **acquire** ([LocationTrackingCoordinator]) — owns the GPS hardware on/off/mode,
 *  - **route** ([LocationFixRouter]) — a captured fix → persist (history) and/or relay (live),
 *  - **access** ([LocationPointStore] + permission) — last-known position, one-shot fix.
 *
 * It deliberately does NOT contain the routing policy — it exposes it. The persist-vs-relay decision
 * stays in [LocationFixRouter] (#835).
 */
class LocationService(
    private val coordinator: LocationTrackingCoordinator,
    private val router: LocationFixRouter,
    private val pointStore: LocationPointStore,
    private val preferences: LocationPreferences,
    private val oneShot: OneShotLocationProvider,
    private val permissionGranted: () -> Boolean = ::isLocationPermissionGranted,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val logger = Logger.withTag("LocationService")

    /** Latest known position (updated for every accepted fix, history on or off). */
    val lastKnown: StateFlow<RawLocationPoint?> get() = pointStore.lastPoint

    /** Whether the user allows persisting location history. */
    val allowLocationHistory: StateFlow<Boolean> get() = preferences.allowLocationHistory

    /**
     * Get the current position. Returns the cached [lastKnown] if it's fresher than [maxAgeMs];
     * otherwise does a one-shot fetch (capped by [timeoutMs]). Does NOT request permission — returns
     * [GpsFixResult.PermissionDenied] when not granted (the caller prompts first if needed).
     *
     * On a fresh fetch the fix is fed back through [LocationFixRouter] so it isn't wasted: it updates
     * the last-known dot and is persisted (if history is on) and/or relayed (if a share is live) —
     * exactly like a fix from the continuous tracker.
     */
    suspend fun getCurrentGps(
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        timeoutMs: Long = OneShotLocationProvider.DEFAULT_TIMEOUT_MS,
    ): GpsFixResult {
        lastKnown.value?.let {
            val age = nowMs() - it.t
            if (age <= maxAgeMs) {
                logger.d { "getCurrentGps: served cached fix (age=${age}ms)" }
                return GpsFixResult.Success(it)
            }
        }
        if (!permissionGranted()) {
            logger.i { "getCurrentGps: location permission not granted" }
            return GpsFixResult.PermissionDenied
        }
        val result = oneShot.getCurrentFix(timeoutMs)
        if (result is GpsFixResult.Success) {
            router.submit(listOf(result.point)) // route so the fetched fix isn't wasted
            logger.i { "getCurrentGps: fetched & routed (src=${result.point.src})" }
        } else {
            logger.i { "getCurrentGps: fetch did not yield a fix ($result)" }
        }
        return result
    }

    /**
     * Keep GPS running while a foreground consumer needs the user's own position (e.g. the live map),
     * independent of the history switch. Release the returned token when done.
     */
    fun acquireDemand(reason: DemandReason): DemandToken = coordinator.acquireTransientDemand(reason)

    /**
     * Re-evaluate the GPS hold — e.g. after location permission is granted while a transient demand
     * is already held (the live map): the held demand now passes the permission gate and GPS arms,
     * so the user's own dot appears without re-acquiring or leaving the screen.
     */
    fun refreshGpsHold() = coordinator.refreshGpsHold()

    /** Turn location-history persistence on/off (also arms/disarms GPS accordingly). */
    suspend fun setAllowLocationHistory(enabled: Boolean) =
        coordinator.setAllowLocationHistory(enabled)

    companion object {
        const val DEFAULT_MAX_AGE_MS = 15_000L
    }
}
