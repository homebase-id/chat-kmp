package id.homebase.core.notifications

import com.mmk.kmpnotifier.notification.NotifierManager

/**
 * Desktop displayer — prefers the Nucleus cross-platform notification backend
 * (Windows toast / macOS user notification / Linux libnotify). Falls back to
 * KMPNotifier's LocalNotifier if Nucleus is unavailable on this platform
 * (e.g. headless Linux with no notification daemon).
 */
actual class RichNotificationDisplayer actual constructor() {

    private val nucleus: NucleusNotificationAdapter? = NucleusNotificationAdapter.createOrNull()

    actual fun show(data: RichNotificationData) {
        val adapter = nucleus
        if (adapter != null) {
            adapter.show(data)
            return
        }
        val notifier = NotifierManager.getLocalNotifier()
        notifier.notify(
            id = data.notificationId,
            title = data.title,
            body = data.body,
            payloadData = data.payloadData,
        )
    }
}
