package id.homebase.core.notifications

/** Platform-specific badge count management for app icon badges. */
expect object BadgeManager {
    fun increment()
    fun clear()
}
