package id.homebase.core.permissions

/**
 * The browser has no OS permission model to consult, so each [PermissionType] needs a chosen
 * answer. Kept in commonMain only so it is unit-testable on the JVM — the wasmJs
 * `createPermissionsManager` is its only caller.
 */
internal fun webPermissionGranted(
    permission: PermissionType,
    notificationGranted: Boolean,
): Boolean = when (permission) {
    PermissionType.NOTIFICATION -> notificationGranted

    // A file picker carries its own grant, so a gate here would only block a path that works.
    PermissionType.GALLERY, PermissionType.GALLERY_LIMITED -> true

    // No browser recorder in this build, so "granted" opens a recording UI that can only fail.
    PermissionType.RECORD_AUDIO -> false

    // Never asked of the browser: camera capture, geolocation and step counting are unimplemented
    // on web and already report their own unavailable state, so a gate would add a second one.
    PermissionType.CAMERA,
    PermissionType.LOCATION,
    PermissionType.LOCATION_ALWAYS,
    PermissionType.ACTIVITY,
        -> true
}
