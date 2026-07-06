package id.homebase.core.location.tracking

import co.touchlab.kermit.Logger
import id.homebase.core.permissions.isLocationPermissionGranted
import kotlin.time.Clock

/** Result of a one-shot current-location request. */
sealed interface GpsFixResult {
    data class Success(val point: RawLocationPoint) : GpsFixResult

    /** Location permission is not granted. The provider never requests it (non-UI). */
    data object PermissionDenied : GpsFixResult

    /** Permission granted but no fix obtainable (location off, no providers, hardware absent). */
    data object Unavailable : GpsFixResult

    /** No fix arrived within the timeout. */
    data object Timeout : GpsFixResult
}

/**
 * A single current-GPS fix, on demand — the consolidated one-shot path (replaces the former
 * Composable `rememberCurrentLocationLauncher` in homebase-chat). Platform-wrapped via
 * [createOneShotLocationProvider]; desktop/web have neither primitive and so always yield
 * [GpsFixResult.Unavailable].
 *
 * Platforms implement only the two **dumb I/O primitives** ([lastKnownFix], [acquireFreshFix]); the
 * **cache-vs-radio policy** lives once in [getCurrentFix] here in common so it can't drift between
 * Android and iOS (#886 review):
 *  - `cacheOnly` (OS battery saver) → serve the OS last-known at any age, never power the radio,
 *  - else serve the OS last-known when it's within `maxAgeMs`, otherwise acquire a fresh fix.
 *
 * It does **not** request permission (it's callable off the UI) — returns [GpsFixResult.PermissionDenied]
 * when not granted. Callers that need to prompt should request permission first (see `rememberCurrentGps`).
 * The returned fix is fed back through [LocationFixRouter] by `LocationService.requestLatestGps` so it's
 * not wasted — it updates the last-known dot and is persisted/relayed per the usual routing.
 */
interface OneShotLocationProvider {
    /** The OS last-known fix (carrying its capture time in [RawLocationPoint.t]), or null if none. No radio. */
    suspend fun lastKnownFix(): RawLocationPoint?

    /** Power the GPS radio for a fresh fix, capped by [timeoutMs]. */
    suspend fun acquireFreshFix(timeoutMs: Long): GpsFixResult

    /**
     * The cache-vs-radio orchestration, shared across platforms (do not override). See the class doc
     * for the policy. [nowMs] is injected so the age check is testable.
     */
    suspend fun getCurrentFix(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        cacheOnly: Boolean = false,
        nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ): GpsFixResult {
        // Result logging lives HERE, in the shared policy method, so Android and iOS emit
        // identical fix/timeout lines by construction (#988) — the platform actuals keep only
        // their own low-level I/O breadcrumbs. Info level: these mirror into Crashlytics
        // breadcrumbs and only fire behind LocationService's staleness + battery gates.
        if (!isLocationPermissionGranted()) {
            Logger.i(tag = TAG) { "getCurrentFix: permission denied" }
            return GpsFixResult.PermissionDenied
        }
        val cached = lastKnownFix()
        // Battery saver: serve whatever the OS already has and never power the radio.
        if (cacheOnly) {
            val result = cached?.let { GpsFixResult.Success(it) } ?: GpsFixResult.Unavailable
            Logger.i(tag = TAG) {
                "getCurrentFix: battery-saver cache-only → " +
                    (cached?.let { "cached fix (age=${nowMs() - it.t}ms)" } ?: "Unavailable")
            }
            return result
        }
        // A cache fresher than the staleness tolerance avoids the radio; an older one falls through
        // so a force-on-stale capture actually acquires a fresh fix instead of echoing stale cache.
        if (cached != null && nowMs() - cached.t <= maxAgeMs) {
            Logger.i(tag = TAG) {
                "getCurrentFix: served cached fix (age=${nowMs() - cached.t}ms ≤ maxAge=${maxAgeMs}ms)"
            }
            return GpsFixResult.Success(cached)
        }
        Logger.d(tag = TAG) {
            "getCurrentFix: acquiring fresh (timeoutMs=$timeoutMs cachedAgeMs=${cached?.let { nowMs() - it.t } ?: "none"})"
        }
        val fresh = acquireFreshFix(timeoutMs)
        Logger.i(tag = TAG) {
            "getCurrentFix: fresh result=" + when (fresh) {
                is GpsFixResult.Success -> "Success src=${fresh.point.src}"
                GpsFixResult.Timeout -> "Timeout (timeoutMs=$timeoutMs)"
                else -> "$fresh"
            }
        }
        return fresh
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L

        /** Default max age for accepting an OS last-known fix before forcing a live acquisition. */
        const val DEFAULT_MAX_AGE_MS = 15_000L

        /** Shared tag for the policy-level result lines (platform actuals keep their own tags). */
        const val TAG = "OneShotLocation"
    }
}

expect fun createOneShotLocationProvider(): OneShotLocationProvider
