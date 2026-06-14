package id.homebase.core.permissions

enum class PermissionType {
    CAMERA,
    GALLERY,
    GALLERY_LIMITED,
    NOTIFICATION,
    RECORD_AUDIO,

    /** Foreground ("while in use") location access. */
    LOCATION,

    /**
     * Background ("allow all the time") location access. On Android this is a
     * separate runtime permission (API 29+) that may only be requested after
     * [LOCATION] is granted; on iOS it is the Always authorization escalation.
     */
    LOCATION_ALWAYS,

    /**
     * Read access to the device address book. Android: `READ_CONTACTS` runtime
     * permission; iOS: `CNContactStore` contacts authorization. Used by the
     * contact-book import flow (mobile only) — desktop/web have no address book
     * and their permission stubs always report granted.
     */
    CONTACTS,

    /**
     * Physical-activity / motion access for the pedometer: Android
     * `ACTIVITY_RECOGNITION` (runtime permission API 29+; auto-granted below),
     * iOS Core Motion. Best-effort — denial just omits step counts.
     */
    ACTIVITY,
}
