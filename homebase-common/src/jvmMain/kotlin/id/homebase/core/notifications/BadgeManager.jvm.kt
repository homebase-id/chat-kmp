package id.homebase.core.notifications

/** Desktop: No badge support. */
actual object BadgeManager {
    actual fun increment() { /* no-op */ }
    actual fun clear() { /* no-op */ }
}
