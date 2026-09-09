package id.homebase.core.notifications

enum class WebPushCapability {
    UNSUPPORTED,

    /** iOS Safari only fires web push for a Home Screen installed PWA; in a tab the APIs are absent. */
    NEEDS_INSTALL,
    DEFAULT,
    GRANTED,
    DENIED,
}

data class WebPushSubscriptionKeys(
    val endpoint: String,
    val auth: String,
    val p256dh: String,
    val expirationTime: Long?,
)

sealed interface WebPushSubscribeResult {
    data class Success(val keys: WebPushSubscriptionKeys) : WebPushSubscribeResult

    /** [code] is the `DOMException.name` (`NotAllowedError`, `AbortError`, …), extracted JS-side. */
    data class Failure(val code: String) : WebPushSubscribeResult
}

interface WebPushBridge {
    fun capability(): WebPushCapability
    suspend fun requestPermission(): WebPushCapability
    suspend fun subscribe(vapidKey: String): WebPushSubscribeResult
    suspend fun currentSubscription(): WebPushSubscriptionKeys?
    suspend fun unsubscribe()

    /** Delivers the raw notification JSON a service-worker click posted back to the page. */
    fun onNotificationClick(handler: (String) -> Unit)

    /**
     * Developer menu only — a server push is displayed by `sw.js` and never reaches Kotlin.
     * Returns null when the browser accepted it, else a code for [describeWebNotificationFailure].
     */
    suspend fun showLocalNotification(title: String, body: String): String?
}

/**
 * Turns a [WebPushBridge.showLocalNotification] failure into a message that says which of
 * "notifications can't display here" and "delivery is broken" the developer is looking at.
 */
fun describeWebNotificationFailure(code: String): String = when (code) {
    "NotAllowedError" ->
        "Notifications are blocked for this site — re-allow them in the browser's site settings"
    "PermissionNotGranted" ->
        "Notification permission not granted yet — enable notifications in Settings first"
    "NeedsInstall" ->
        "iOS Safari shows notifications only once the app is added to the Home Screen"
    "Unsupported" ->
        "This browser can't show notifications (needs HTTPS plus service worker support)"
    "NoServiceWorker" ->
        "No service worker registered — sw.js failed to load"
    "TimeoutError" ->
        "The browser never answered the showNotification call"
    else -> "The browser refused to show the notification: $code"
}

expect fun webPushBridge(): WebPushBridge?

enum class WebPushHealth {
    UNSUPPORTED,
    NEEDS_INSTALL,
    BLOCKED,
    NOT_SUBSCRIBED,
    NEEDS_REPAIR,
    SUBSCRIBED,
}

/**
 * Replaces [NotificationService.verifySubscription] for web: the server redacts `endpoint`/`auth`/
 * `p256DH`, so a healthy browser subscription reads back with a null `firebaseDeviceToken` and the
 * FCM check calls it a permanent TOKEN_MISMATCH. Presence on both sides is all that can be checked.
 */
internal fun decideWebPushHealth(
    capability: WebPushCapability,
    hasBrowserSubscription: Boolean,
    hasServerSubscription: Boolean,
): WebPushHealth = when (capability) {
    WebPushCapability.UNSUPPORTED -> WebPushHealth.UNSUPPORTED
    WebPushCapability.NEEDS_INSTALL -> WebPushHealth.NEEDS_INSTALL
    WebPushCapability.DENIED -> WebPushHealth.BLOCKED
    WebPushCapability.DEFAULT -> WebPushHealth.NOT_SUBSCRIBED
    WebPushCapability.GRANTED ->
        if (hasBrowserSubscription && hasServerSubscription) WebPushHealth.SUBSCRIBED
        else WebPushHealth.NEEDS_REPAIR
}
