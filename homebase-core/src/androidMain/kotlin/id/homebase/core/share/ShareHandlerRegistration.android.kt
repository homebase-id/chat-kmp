package id.homebase.core.share

actual fun registerShareHandler(handler: (conversationId: String) -> Unit) {
    // No-op on Android — sharing is handled in-process by ShareReceiverActivity
}

actual fun unregisterShareHandler() {
    // No-op
}

actual fun registerMomentShareHandler(handler: () -> Unit) {
    // No-op on Android — sharing into a new moment is handled in-process by ShareReceiverActivity
}

actual fun unregisterMomentShareHandler() {
    // No-op
}
