package id.homebase.chat.location

import androidx.compose.runtime.Composable

/**
 * Composable launcher for one-shot GPS retrieval. Calling [launch] requests the location
 * permission (when needed), fetches a single fix, and invokes the callback with a typed
 * [LocationResult] so the caller can show appropriate user feedback (permission-denied vs
 * just-couldn't-get-a-fix).
 */
interface LocationLauncher {
    fun launch()
}

/** Outcome of a one-shot GPS fetch attempt. */
sealed interface LocationResult {
    /** Got a fix. */
    data class Success(val fix: LocationFix) : LocationResult

    /** User denied (or has previously permanently denied) location permission, OR the platform
     *  doesn't support sharing (Desktop). UI should suggest enabling it in Settings. */
    data object PermissionDenied : LocationResult

    /** Permission was granted but the platform couldn't produce a fix — emulator without a mock
     *  location, indoor with no signal, location services disabled at OS level, fused-provider
     *  failure, etc. UI should suggest trying outdoors / checking that Location is on. */
    data object Unavailable : LocationResult
}

@Composable
expect fun rememberCurrentLocationLauncher(
    onResult: (LocationResult) -> Unit,
): LocationLauncher
