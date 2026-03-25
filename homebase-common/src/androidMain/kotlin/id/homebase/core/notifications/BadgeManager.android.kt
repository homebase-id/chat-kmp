package id.homebase.core.notifications

import android.app.NotificationManager
import android.content.Context

/**
 * Android badge management.
 * Badge count is managed per-notification via setNumber() on the notification builder.
 * clear() cancels all notifications (resetting badge count).
 */
actual object BadgeManager {
    internal var badgeCount = 0
        private set

    actual fun increment() {
        badgeCount++
    }

    actual fun clear() {
        badgeCount = 0
        val context = RichNotificationDisplayer.appContext ?: return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()
    }
}
