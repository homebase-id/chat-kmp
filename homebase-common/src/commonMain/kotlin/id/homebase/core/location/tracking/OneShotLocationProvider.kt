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
    suspend fun getCurrentFix(timeoutMs: Long = DEFAULT_TIMEOUT_MS): GpsFixResult

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}

expect fun createOneShotLocationProvider(): OneShotLocationProvider
