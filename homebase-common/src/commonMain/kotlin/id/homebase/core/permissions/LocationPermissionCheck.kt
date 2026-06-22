package id.homebase.core.permissions

/**
 * Read-only check of whether foreground ("while in use") location is currently granted, without an
 * Activity or a permission launcher.
 *
 * [createPermissionsManager] is a `@Composable` that can only run inside composition (it needs
 * `LocalActivity` + a `rememberLauncherForActivityResult` launcher), so it cannot be a Koin
 * singleton. Non-UI code that only needs to *read* the location grant — DI singletons, services —
 * uses this instead. The grant is a process-wide system state, so no instance/context needs to be
 * threaded through: each platform reads it from the OS (Android via the application context,
 * iOS via the shared CLLocationManager authorization status).
 *
 * See `id.homebase.chat.services.livelocation.LiveShareReadiness`.
 */
expect fun isLocationPermissionGranted(): Boolean
