package id.homebase.core.permissions

import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse

// Mirrors IOSPermissionsManager.isPermissionGranted(PermissionType.LOCATION), reading the same
// process-wide CLLocationManager authorization status (a synchronous OS read — no prompt).
actual fun isLocationPermissionGranted(): Boolean {
    val status = LocationPermissionRequester.status
    return status == kCLAuthorizationStatusAuthorizedWhenInUse ||
        status == kCLAuthorizationStatusAuthorizedAlways
}
