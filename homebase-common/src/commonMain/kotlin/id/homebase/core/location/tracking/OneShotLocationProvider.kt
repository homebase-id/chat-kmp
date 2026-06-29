package id.homebase.core.location.tracking

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
 * [createOneShotLocationProvider]; desktop/web return [GpsFixResult.Unavailable].
 *
 * It does **not** request permission (it's callable off the UI) — returns [GpsFixResult.PermissionDenied]
 * when not granted. Callers that need to prompt should request permission first (see `rememberCurrentGps`).
 * The returned fix is fed back through [LocationFixRouter] by `LocationService.getCurrentGps` so it's
 * not wasted — it updates the last-known dot and is persisted/relayed per the usual routing.
 */
interface OneShotLocationProvider {
    /**
     * Return a current fix. A platform may serve a recent OS last-known fix without powering the GPS
     * radio, but ONLY if it is no older than [maxAgeMs]; an older cache must fall through to a live
     * acquisition (capped by [timeoutMs]). The age bound is what makes a force-on-stale capture
     * actually spend battery on a fresh fix instead of echoing a stale cached one (#878 / #886 review).
     *
     * When [cacheOnly] is true (OS battery saver on), return the OS last-known fix at ANY age and
     * NEVER power the radio — return [GpsFixResult.Unavailable] when there is no cached fix at all.
     */
    suspend fun getCurrentFix(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        cacheOnly: Boolean = false,
    ): GpsFixResult

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L

        /** Default max age for accepting an OS last-known fix before forcing a live acquisition. */
        const val DEFAULT_MAX_AGE_MS = 15_000L
    }
}

expect fun createOneShotLocationProvider(): OneShotLocationProvider
