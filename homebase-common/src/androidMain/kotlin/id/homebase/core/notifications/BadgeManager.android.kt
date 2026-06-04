package id.homebase.core.notifications

import android.app.NotificationManager
import android.content.Context

/**
 * Android badge management.
 * Badge count is managed per-notification via setNumber() on the notification builder.
 */
actual object BadgeManager {
    internal var badgeCount = 0
        private set

    actual fun increment() {
        badgeCount++
    }

    actual fun resetCount() {
        badgeCount = 0
    }

    actual fun cancelAll() {
        badgeCount = 0
        notificationManager()?.cancelAll()
    }

    actual fun cancelConversationNotifications(messageId: Int, summaryId: Int) {
        val nm = notificationManager() ?: return
        nm.cancel(messageId)
        nm.cancel(summaryId)
    }

    private fun notificationManager(): NotificationManager? {
        val context = RichNotificationDisplayer.appContext ?: return null
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
}
