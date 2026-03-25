package id.homebase.core.notifications

import com.mmk.kmpnotifier.notification.NotifierManager
import kotlin.random.Random

/**
 * Desktop fallback — delegates to KMPNotifier's LocalNotifier.
 * Desktop platforms (macOS/Windows/Linux) don't support rich notification styles.
 */
actual class RichNotificationDisplayer actual constructor() {

    actual fun show(data: RichNotificationData) {
        val notifier = NotifierManager.getLocalNotifier()
        notifier.notify(
            id = data.notificationId,
            title = data.title,
            body = data.body,
            payloadData = data.payloadData
        )
    }
}
