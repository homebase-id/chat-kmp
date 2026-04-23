package id.homebase.core.notifications

import com.mmk.kmpnotifier.notification.PayloadData

/**
 * Bridge used by platform notification backends (e.g. Nucleus on JVM) to deliver
 * click events back into [NotificationService] without requiring direct DI.
 *
 * [NotificationService] sets [handler] during init; platform displayers invoke
 * [onClick] on notification activation.
 */
object NotificationClickRouter {
    @Volatile
    var handler: ((PayloadData) -> Unit)? = null

    fun onClick(data: PayloadData) {
        handler?.invoke(data)
    }
}
