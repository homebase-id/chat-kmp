package id.homebase.core.permissions

// Web's PermissionsManager reports every permission as granted; match that here so the readiness
// gate behaves consistently with the existing web stub.
actual fun isLocationPermissionGranted(): Boolean = true
