package id.homebase.core.notifications

/**
 * Platform-specific rich notification displayer.
 *
 * Android: Uses NotificationCompat.MessagingStyle with circular avatars, grouping, and actions.
 * iOS: Uses UNMutableNotificationContent with communication notification style.
 * Desktop: Falls back to KMPNotifier's LocalNotifier.
 * Web: No-op.
 */
expect class RichNotificationDisplayer() {
    fun show(data: RichNotificationData)
}
