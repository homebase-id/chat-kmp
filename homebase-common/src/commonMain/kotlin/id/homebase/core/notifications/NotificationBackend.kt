package id.homebase.core.notifications

/**
 * Drop-in alias for kmpnotifier's `PayloadData`. Matches the upstream
 * `typealias PayloadData = Map<String, Any?>` exactly so commonMain call
 * sites that handled `PayloadData` previously stay byte-equivalent — the only
 * change is the import.
 *
 * Step 9c of the WASM pre-flight plan moves the kmpnotifier types off
 * commonMain so the wasmJs target can stop pulling a JVM/Android/iOS-only
 * artifact.
 */
typealias PayloadData = Map<String, Any?>

/**
 * Listener interface that mirrors `NotifierManager.Listener` but lives in
 * commonMain so [NotificationService] can subscribe without importing
 * kmpnotifier. All callbacks default to no-ops — implementations only need to
 * override what they care about.
 */
interface NotificationListener {
    fun onNewToken(token: String) {}
    fun onPushNotificationWithPayloadData(title: String?, body: String?, data: PayloadData) {}
    fun onNotificationClicked(data: PayloadData) {}
    fun onPushNotification(title: String?, body: String?) {}
    fun onPayloadData(data: PayloadData) {}
}

/**
 * Platform-agnostic facade over the push / local-notification machinery.
 * Bound through Koin in each platform's `platformModule()`. On Android / JVM /
 * iOS the implementation delegates to `com.mmk.kmpnotifier.notification.NotifierManager`;
 * on wasmJs the implementation is a no-op.
 */
interface NotificationBackend {
    fun addListener(listener: NotificationListener)
    fun setLogger(log: (String) -> Unit)
    /**
     * [payloadData] is `Map<String, String>` (not [PayloadData]) because the
     * underlying `LocalNotifier.notify` signature constrains values to
     * strings — only the listener side widens to `Any?`.
     */
    fun showLocalNotification(
        id: Int,
        title: String,
        body: String,
        payloadData: Map<String, String>,
    )
    suspend fun getPushToken(): String?
    suspend fun deletePushToken()
}
