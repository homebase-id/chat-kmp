package id.homebase.core.share

/**
 * Registers a share intent handler on platforms that support it (iOS).
 * No-op on Android/Desktop/Web where sharing is handled in-process.
 */
expect fun registerShareHandler(handler: (conversationId: String) -> Unit)

/** Clears the share handler registration. */
expect fun unregisterShareHandler()
