package id.homebase.core.permissions

// Desktop has no runtime location-permission model; PermissionsManager.jvm reports every
// permission as granted, so the readiness gate matches.
actual fun isLocationPermissionGranted(): Boolean = true
