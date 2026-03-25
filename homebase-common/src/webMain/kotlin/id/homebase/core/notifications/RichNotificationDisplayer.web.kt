package id.homebase.core.notifications

/** Web: No-op — browser notifications are handled by the service worker / KMPNotifier. */
actual class RichNotificationDisplayer actual constructor() {
    actual fun show(data: RichNotificationData) {
        // Web notifications not implemented in KMP target
    }
}
