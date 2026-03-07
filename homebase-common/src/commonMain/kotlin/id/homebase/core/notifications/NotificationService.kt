package id.homebase.core.notifications

import co.touchlab.kermit.Logger
import com.mmk.kmpnotifier.notification.NotifierManager
import com.mmk.kmpnotifier.notification.PayloadData
import id.homebase.api.client.notifications.PushNotificationApi
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.util.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Central notification service that wraps KMPNotifier and handles incoming push/local
 * notifications. Register as a singleton in Koin.
 */
class NotificationService(private val api: PushNotificationApi, private val scope: CoroutineScope) {

    private var isListening = false

    init {
        startListening()
    }

    /**
     * Initializes notification listeners. Call after NotifierManager.initialize() has been called
     * on the platform side.
     */
    fun startListening() {
        if (isListening) return
        isListening = true

        NotifierManager.addListener(object : NotifierManager.Listener {
            override fun onNewToken(token: String) {
                super.onNewToken(token)
                Logger.i(tag = "NotificationService") { "New push token: $token" }
                registerToken(token)
            }

            override fun onPushNotificationWithPayloadData(
                title: String?, body: String?, data: PayloadData
            ) {
                super.onPushNotificationWithPayloadData(title, body, data)
                Logger.i(tag = "NotificationService") {
                    "Push received — title=$title body=$body data=$data"
                }
                handleIncomingPayload(data)
            }

            override fun onNotificationClicked(data: PayloadData) {
                Logger.i(tag = "NotificationService") { "Notification clicked: $data" }
                // TODO: Navigate to relevant screen based on payload
            }

            override fun onPushNotification(title: String?, body: String?) {
                super.onPushNotification(title, body)
                Logger.i(tag = "NotificationService") {
                    "Push received — title=$title body=$body"
                }
                // TODO: onPushNotificationHandle
            }

            override fun onPayloadData(data: PayloadData) {
                super.onPayloadData(data)
                Logger.i(tag = "NotificationService") { "Payload received: $data" }
                // TODO: onPayloadDataHandle
            }
        })

        NotifierManager.setLogger { message -> Logger.d(tag = "KMPNotifier") { message } }
    }

    private fun registerToken(token: String) {
        scope.launch {
            try {
                val platformName = Platform.osName
                val friendlyName = "${Platform.osName} | ${Platform.osVersion}"

                Logger.i(tag = "NotificationService") {
                    "Registering token with server... ($friendlyName)"
                }
                api.subscribe(
                    deviceToken = token, devicePlatform = platformName, friendlyName = friendlyName
                )
                Logger.i(tag = "NotificationService") { "Token registered successfully" }
            } catch (e: Exception) {
                Logger.e(tag = "NotificationService") { "Failed to register token: ${e.message}" }
            }
        }
    }

    /** Parses the raw payload data map and creates a local notification display. */
    private fun handleIncomingPayload(data: PayloadData) {
        try {
            val dataString = data["data"] as? String ?: return
            val notification = OdinSystemSerializer.deserialize<PushNotification>(dataString)

            val displayName = notification.senderId // TODO: Fetch display name from profile
            val appName = notification.appDisplayName ?: "Homebase"
            val bodyText = NotificationBodyFormer.form(
                payload = notification,
                hasMultiple = false,
                appName = appName,
                senderName = displayName
            )

            showLocalNotification(title = appName, body = bodyText, data = data)
        } catch (e: Exception) {
            Logger.e(tag = "NotificationService") { "Failed to parse notification: ${e.message}" }
        }
    }

    /** Displays a local notification using KMPNotifier. */
    fun showLocalNotification(
        title: String, body: String, data: PayloadData = emptyMap<String, Any>()
    ) {
        val notifier = NotifierManager.getLocalNotifier()
        val payloadMap: Map<String, String> =
            data.entries.associate { it.key to it.value.toString() }
        notifier.notify(
            id = Random.nextInt(0, Int.MAX_VALUE),
            title = title,
            body = body,
            payloadData = payloadMap
        )
    }

    /** Gets the current push notification token, or null if not available. */
    suspend fun getToken(): String? {
        return try {
            NotifierManager.getPushNotifier().getToken()
        } catch (e: Exception) {
            Logger.w(tag = "NotificationService") { "Failed to get push token: ${e.message}" }
            null
        }
    }

    /** Deletes the current push notification token and unsubscribes from server. */
    suspend fun deleteToken() {
        try {
            Logger.i(tag = "NotificationService") { "Unsubscribing token..." }
            api.unsubscribe()
            NotifierManager.getPushNotifier().deleteMyToken()
            Logger.i(tag = "NotificationService") { "Token deleted and unsubscribed" }
        } catch (e: Exception) {
            Logger.w(tag = "NotificationService") { "Failed to delete token/unsubscribe: ${e.message}" }
        }
    }

    /** Re-registers push notifications by deleting and re-fetching the token. */
    suspend fun reRegister(): String? {
        deleteToken()
        // getToken() will trigger onNewToken listener if a new token is generated,
        // or we might need to manually call registerToken if getToken returns immediately.
        // But KMPNotifier onNewToken is usually called when underlying token changes.
        // If we simply call getToken(), it returns the token.
        // If we deleted it, getToken() should fetch a new one.
        val newToken = getToken()
        if (newToken != null) {
            registerToken(newToken)
        }
        return newToken
    }
}
