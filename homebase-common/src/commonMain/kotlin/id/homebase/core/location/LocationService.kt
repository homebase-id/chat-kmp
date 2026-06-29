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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Why an on-demand GPS request needs [requestLatestGps] (and a reason). The continuous tracker only
 * arms *interval* updates on foreground; a brief app-open closes before the first interval fires, so
 * a backgrounded moving session (e.g. sailing) records almost nothing (#878 root cause A). The fix is
 * to force a one-shot fix on app-open / push-receipt when the last point is stale. To keep that from
 * draining the battery when triggers cluster, every on-demand path funnels through ONE idempotent,
 * battery-aware primitive instead of calling the one-shot provider directly.
 */
enum class GpsRequestReason(
    /** Serve the last-known fix without touching hardware if it's at most this old. */
    val staleAfterMs: Long,
    /** How long the one-shot acquisition may wait for a fix. */
    val timeoutMs: Long,
) {
    /** App brought to the foreground — afford a longer wait + high accuracy, want a recent point. */
    AppForeground(staleAfterMs = 60_000L, timeoutMs = 15_000L),

    /** Foreground on-demand (the live map, `rememberCurrentGps`) — tight freshness. */
    LiveMap(staleAfterMs = 30_000L, timeoutMs = 15_000L),

    /** Background FCM wake — short execution window: short timeout, fall back fast to last-known. */
    PushReceived(staleAfterMs = 120_000L, timeoutMs = 5_000L),
}

/**
 * The single public entry point for "this device's location", callable from anywhere in the app. It
 * composes the three otherwise-separate concerns so callers don't reach into them individually:
 *  - **acquire** ([LocationTrackingCoordinator]) — owns the GPS hardware on/off/mode,
 *  - **route** ([LocationFixRouter]) — a captured fix → persist (history) and/or relay (live),
 *  - **access** ([LocationPointStore] + permission) — last-known position, one-shot fix.
 *
 * It deliberately does NOT contain the routing policy — it exposes it. The persist-vs-relay decision
 * stays in [LocationFixRouter] (#835).
 *
 * **[requestLatestGps] is the one on-demand capture path** — so the battery + timing policy lives in
 * one place (#878). Automatic triggers (app-open, push, future activity transitions) MUST go through
 * the gated [forceCaptureIfTracking] (which delegates here); only interactive/transient consumers
 * (the live map) call [requestLatestGps] directly. Either way every fix flows through here:
 *  - **single-flight:** concurrent/duplicate calls coalesce onto the in-flight acquisition,
 *  - **battery-aware:** a min interval between real acquisitions + reuse of a recent last-known,
 *  - **timing-aware:** foreground reasons wait longer at high accuracy; background reasons use a short
 *    timeout and fall back fast to last-known.
 */
class LocationService(
    private val coordinator: LocationTrackingCoordinator,
    private val router: LocationFixRouter,
    private val pointStore: LocationPointStore,
    private val preferences: LocationPreferences,
    private val oneShot: OneShotLocationProvider,
    private val scope: CoroutineScope,
    private val permissionGranted: () -> Boolean = ::isLocationPermissionGranted,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** OS battery saver on → acquire cache-only (no radio). Wired in DI to DeviceSensors. */
    private val powerSaveMode: () -> Boolean = { false },
) {
    private val logger = Logger.withTag("LocationService")

    /** Latest known position (updated for every accepted fix, history on or off). */
    val lastKnown: StateFlow<RawLocationPoint?> get() = pointStore.lastPoint

    /** Whether the user allows persisting location history. */
    val allowLocationHistory: StateFlow<Boolean> get() = preferences.allowLocationHistory

    // Single-flight + battery guard state, all touched only under [acquireMutex].
    private val acquireMutex = Mutex()
    private var inFlight: Deferred<GpsFixResult>? = null
    private var lastAcquireStartMs = 0L

    /**
     * Get the current position, idempotently and battery-aware. Returns the cached [lastKnown] if it's
     * fresher than [GpsRequestReason.staleAfterMs]; otherwise does a one-shot fetch (capped by
     * [GpsRequestReason.timeoutMs]). Does NOT request permission — returns [GpsFixResult.PermissionDenied]
     * when not granted (the caller prompts first if needed).
     *
     * **Ungated — for interactive/transient consumers only (e.g. the live map, which holds its own
     * transient demand and legitimately wants a fix even with history off and no share).** This will
     * power the radio regardless of whether any persistent consumer is recording. **Automatic
     * triggers (app-open, push, future activity transitions) must call [forceCaptureIfTracking]
     * instead**, so the "only when something is recording" gate stays in one place.
     *
     * Guarantees (so clustered triggers don't each acquire):
     *  - **single-flight:** if an acquisition is already in flight, this awaits and returns *its* result
     *    rather than starting a parallel GPS lock,
     *  - **battery min-interval:** if a real acquisition started within [MIN_ACQUISITION_INTERVAL_MS],
     *    skip acquiring and reuse the last-known fix (so an app-open plus several pushes in a minute
     *    coalesce to at most one acquisition).
     *
     * On a fresh fetch the fix is fed back through [LocationFixRouter] so it isn't wasted: it updates
     * the last-known dot and is persisted (if history is on) and/or relayed (if a share is live).
     * Because every [GpsRequestReason.staleAfterMs] is larger than the store's 20 s stationary-noise
     * window, a forced fix is never dropped by that filter — no special-casing needed.
     */
    suspend fun requestLatestGps(reason: GpsRequestReason): GpsFixResult {
        lastKnown.value?.let {
            val age = nowMs() - it.t
            if (age <= reason.staleAfterMs) {
                logger.d { "requestLatestGps($reason): served last-known (age=${age}ms)" }
                return GpsFixResult.Success(it)
            }
        }
        if (!permissionGranted()) {
            logger.i { "requestLatestGps($reason): location permission not granted" }
            return GpsFixResult.PermissionDenied
        }

        val deferred = acquireMutex.withLock {
            // Coalesce onto an acquisition already running.
            inFlight?.let { if (it.isActive) return@withLock it }
            // Battery guard: too soon since the last real acquisition — reuse last-known instead.
            val sinceLast = nowMs() - lastAcquireStartMs
            if (lastAcquireStartMs != 0L && sinceLast < MIN_ACQUISITION_INTERVAL_MS) {
                val fallback = lastKnown.value?.let { GpsFixResult.Success(it) } ?: GpsFixResult.Timeout
                logger.i {
                    "requestLatestGps($reason): battery guard (since=${sinceLast}ms < " +
                        "${MIN_ACQUISITION_INTERVAL_MS}ms) → reuse last-known ($fallback)"
                }
                return fallback
            }
            lastAcquireStartMs = nowMs()
            scope.async { acquireAndRoute(reason) }.also { inFlight = it }
        }
        return try {
            deferred.await()
        } finally {
            acquireMutex.withLock { if (inFlight === deferred) inFlight = null }
        }
    }

    /** Run the one-shot acquisition and route a successful fix through the pipeline. */
    private suspend fun acquireAndRoute(reason: GpsRequestReason): GpsFixResult {
        // Bound the OS last-known cache by the same staleness tolerance: if the cache is fresher than
        // staleAfterMs we reuse it (no radio), otherwise the provider powers the radio for a fresh fix.
        // Without this, a stale OS cache would satisfy the "force" and the radio would never run (#886).
        // Under OS battery saver we go cache-only — serve the OS last-known and never power the radio.
        val cacheOnly = powerSaveMode()
        if (cacheOnly) logger.d { "requestLatestGps($reason): battery saver on — cache-only (no radio)" }
        val result = oneShot.getCurrentFix(
            timeoutMs = reason.timeoutMs,
            maxAgeMs = reason.staleAfterMs,
            cacheOnly = cacheOnly,
            nowMs = nowMs,
        )
        if (result is GpsFixResult.Success) {
            router.submit(listOf(result.point)) // route so the fetched fix isn't wasted
            logger.i { "requestLatestGps($reason): fetched & routed (src=${result.point.src})" }
        } else {
            logger.i { "requestLatestGps($reason): fetch did not yield a fix ($result)" }
        }
        return result
    }

    /**
     * Force a capture only when a persistent consumer (history or a live share) actually wants GPS —
     * the gate for the automatic triggers (app-open, push) so we never spin up the radio when nobody
     * is recording. The live map calls [requestLatestGps] directly (it holds a transient demand).
     * Returns null when nothing wants a capture.
     */
    suspend fun forceCaptureIfTracking(reason: GpsRequestReason): GpsFixResult? {
        if (!coordinator.isCaptureWanted()) {
            logger.d { "forceCaptureIfTracking($reason): skipped — no persistent consumer wants GPS" }
            return null
        }
        return requestLatestGps(reason)
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
        /**
         * Minimum spacing between real GPS acquisitions across all on-demand triggers. Clustered
         * triggers within this window reuse the last-known fix instead of locking the radio again.
         */
        const val MIN_ACQUISITION_INTERVAL_MS = 60_000L
    }
}
