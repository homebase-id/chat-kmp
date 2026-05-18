package id.homebase.core.notifications

import com.mmk.kmpnotifier.notification.NotifierManager

/**
 * Android implementation of [NotificationBackend] that delegates to
 * `com.mmk.kmpnotifier.notification.NotifierManager`. Assumes
 * `NotifierManager.initialize(...)` has already been called by the entry
 * point (`androidApp/MainApplication.kt`). Same shape duplicated across the
 * three real-platform actuals — see step 9c of the WASM pre-flight plan; no
 * common ancestor source set covers Android/JVM/Apple.
 */
class KMPNotifierBackend : NotificationBackend {
    override fun addListener(listener: NotificationListener) {
        NotifierManager.addListener(object : NotifierManager.Listener {
            override fun onNewToken(token: String) =
                listener.onNewToken(token)
            override fun onPushNotificationWithPayloadData(
                title: String?, body: String?, data: com.mmk.kmpnotifier.notification.PayloadData,
            ) = listener.onPushNotificationWithPayloadData(title, body, data)
            override fun onNotificationClicked(data: com.mmk.kmpnotifier.notification.PayloadData) =
                listener.onNotificationClicked(data)
            override fun onPushNotification(title: String?, body: String?) =
                listener.onPushNotification(title, body)
            override fun onPayloadData(data: com.mmk.kmpnotifier.notification.PayloadData) =
                listener.onPayloadData(data)
        })
    }

    override fun setLogger(log: (String) -> Unit) {
        NotifierManager.setLogger { log(it) }
    }

    override fun showLocalNotification(id: Int, title: String, body: String, payloadData: Map<String, String>) {
        NotifierManager.getLocalNotifier().notify(
            id = id, title = title, body = body, payloadData = payloadData,
        )
    }

    override suspend fun getPushToken(): String? =
        NotifierManager.getPushNotifier().getToken()

    override suspend fun deletePushToken() {
        NotifierManager.getPushNotifier().deleteMyToken()
    }
}
