package id.homebase.core.permissions

import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse

/**
 * CoreLocation-backed location permission read. `authorizationStatus` is a plain
 * instance property (no delegate, no prompt), so reading it off-composition is
 * safe and needs none of the request machinery in [IOSPermissionsManager].
 *
 * Mirrors `IOSPermissionsManager.isPermissionGranted(PermissionType.LOCATION)`:
 * both while-in-use and always count as granted.
 */
fun iosLocationPermissionQuery(): LocationPermissionQuery =
    LocationPermissionQuery {
        val status = CLLocationManager().authorizationStatus
        status == kCLAuthorizationStatusAuthorizedWhenInUse ||
            status == kCLAuthorizationStatusAuthorizedAlways
    }
