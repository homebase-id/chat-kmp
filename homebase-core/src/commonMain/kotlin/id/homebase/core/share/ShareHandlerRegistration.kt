package id.homebase.core.share

/**
 * Registers a share intent handler on platforms that support it (iOS).
 * No-op on Android/Desktop/Web where sharing is handled in-process.
 */
expect fun registerShareHandler(handler: (conversationId: String) -> Unit)

/** Clears the share handler registration. */
expect fun unregisterShareHandler()

/**
 * Registers a "New Moment" share handler on platforms that support it (iOS).
 * Invoked when the share extension hands a media share off to the moments
 * composer. No-op on Android/Desktop/Web where sharing is handled in-process.
 */
expect fun registerMomentShareHandler(handler: () -> Unit)

/** Clears the moment share handler registration. */
expect fun unregisterMomentShareHandler()
