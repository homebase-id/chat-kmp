package id.homebase.core.permissions

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import id.homebase.api.ActivityProvider

// Mirrors AndroidPermissionsManager.isPermissionGranted(PermissionType.LOCATION): coarse-only
// ("approximate") still counts as while-in-use access. checkSelfPermission needs only the
// application context — no Activity or launcher — so it is safe to call off the composition.
actual fun isLocationPermissionGranted(): Boolean {
    val context = ActivityProvider.requireApplicationContext()
    return ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}
