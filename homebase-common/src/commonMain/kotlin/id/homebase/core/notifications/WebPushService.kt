package id.homebase.core.notifications

import co.touchlab.kermit.Logger
import id.homebase.api.client.notifications.PushNotificationApi
import id.homebase.api.client.notifications.WebPushSubscriptionRequest
import id.homebase.core.settings.UserPreferences
import id.homebase.core.util.Platform

private const val TAG = "WebPushService"

/**
 * Browser push, parallel to [NotificationService] rather than inside it: `getPushToken(): String?`
 * models one opaque FCM token, while a web subscription is an endpoint + two keys, and none of the
 * token retry / verify / re-register path transfers.
 */
class WebPushService(
    private val api: PushNotificationApi,
    private val userPreferences: UserPreferences,
) {
    private val bridge = webPushBridge()

    val isSupported: Boolean get() = bridge != null

    init {
        bridge?.onNotificationClick { json -> NotificationClickRouter.onClick(mapOf("data" to json)) }
    }

    /** Repairs a granted-but-unsubscribed browser in passing; throws if the server is unreachable. */
    suspend fun evaluate(): WebPushHealth {
        val bridge = bridge ?: return WebPushHealth.UNSUPPORTED
        val health = decideWebPushHealth(
            capability = bridge.capability(),
            hasBrowserSubscription = bridge.currentSubscription() != null,
            hasServerSubscription = api.getSubscription() != null,
        )
        if (health != WebPushHealth.NEEDS_REPAIR) return health
        return if (subscribeAndRegister(bridge)) WebPushHealth.SUBSCRIBED else WebPushHealth.NEEDS_REPAIR
    }

    suspend fun shouldOffer(): Boolean =
        shouldOfferWebPush(evaluate(), userPreferences.webPushOfferSuppressed)

    fun suppressOffer() {
        userPreferences.webPushOfferSuppressed = true
    }

    /** Must be called from a user gesture — every browser gates the permission prompt on one. */
    suspend fun enable(): WebPushHealth {
        val bridge = bridge ?: return WebPushHealth.UNSUPPORTED
        val capability =
            if (bridge.capability() == WebPushCapability.DEFAULT) bridge.requestPermission()
            else bridge.capability()
        return when (capability) {
            WebPushCapability.GRANTED ->
                if (subscribeAndRegister(bridge)) WebPushHealth.SUBSCRIBED else WebPushHealth.NEEDS_REPAIR
            WebPushCapability.DENIED -> WebPushHealth.BLOCKED
            WebPushCapability.NEEDS_INSTALL -> WebPushHealth.NEEDS_INSTALL
            WebPushCapability.UNSUPPORTED -> WebPushHealth.UNSUPPORTED
            WebPushCapability.DEFAULT -> WebPushHealth.NOT_SUBSCRIBED
        }
    }

    /** The server side is already dropped by [NotificationService.deleteToken]. */
    suspend fun disableLocally() {
        bridge?.unsubscribe()
    }

    private suspend fun subscribeAndRegister(bridge: WebPushBridge): Boolean {
        val vapidKey = api.getVapidPublicKey()
        if (vapidKey.isBlank()) {
            Logger.w(tag = TAG) { "Identity returned no VAPID key — web push unavailable" }
            return false
        }
        return when (val result = bridge.subscribe(vapidKey)) {
            is WebPushSubscribeResult.Success -> {
                api.subscribeWebPush(
                    WebPushSubscriptionRequest(
                        friendlyName = "${Platform.osName} | ${Platform.osVersion}",
                        endpoint = result.keys.endpoint,
                        expirationTime = result.keys.expirationTime,
                        auth = result.keys.auth,
                        p256DH = result.keys.p256dh,
                    )
                )
                Logger.i(tag = TAG) { "Web push subscription registered" }
                true
            }

            is WebPushSubscribeResult.Failure -> {
                Logger.w(tag = TAG) { "Browser refused the push subscription: ${result.code}" }
                false
            }
        }
    }
}
