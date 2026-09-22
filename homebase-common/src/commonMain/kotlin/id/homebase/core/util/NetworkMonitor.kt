package id.homebase.core.util

/**
 * Metered-vs-unmetered for the active network. The WebSocket-derived
 * `AuthConnectionCoordinator.isOnline` only answers "connected at all".
 */
fun interface NetworkMonitor {
    /** True only when the active network is known to be unmetered. Unknown counts as metered. */
    fun isUnmetered(): Boolean
}
