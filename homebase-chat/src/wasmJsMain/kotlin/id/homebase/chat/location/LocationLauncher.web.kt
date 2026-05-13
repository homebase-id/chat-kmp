package id.homebase.chat.location

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// Web actual. The browser's Geolocation API (navigator.geolocation) could be
// wired here in a follow-up, but it requires HTTPS and an explicit permission
// prompt — out of scope for the pre-flight. Report PermissionDenied so the UI
// surfaces a "Location not available" path.
@Composable
actual fun rememberCurrentLocationLauncher(
    onResult: (LocationResult) -> Unit,
): LocationLauncher = remember(onResult) {
    object : LocationLauncher {
        override fun launch() {
            onResult(LocationResult.PermissionDenied)
        }
    }
}
