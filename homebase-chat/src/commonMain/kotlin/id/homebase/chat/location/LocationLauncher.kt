package id.homebase.chat.location

import androidx.compose.runtime.Composable

/**
 * Composable launcher for one-shot GPS retrieval. Calling [launch] requests the location
 * permission (when needed), fetches a single fix, and invokes the callback registered with
 * [rememberCurrentLocationLauncher].
 *
 * Returns `null` to the callback when the permission was denied, the platform doesn't support
 * sharing (Desktop), or the fix could not be obtained.
 */
interface LocationLauncher {
    fun launch()
}

@Composable
expect fun rememberCurrentLocationLauncher(
    onResult: (LocationFix?) -> Unit,
): LocationLauncher
