package id.homebase.core.notifications

// No push / local notifications on wasmJs yet — kmpnotifier has no wasmJs
// artifact. Returning a no-op backend lets NotificationService construct
// without throwing; the browser Notification API or a service-worker shim
// can come later if/when web push matters.
class NoopNotificationBackend : NotificationBackend {
    override fun addListener(listener: NotificationListener) {}
    override fun setLogger(log: (String) -> Unit) {}
    override fun showLocalNotification(id: Int, title: String, body: String, payloadData: Map<String, String>) {}
    override suspend fun getPushToken(): String? = null
    override suspend fun deletePushToken() {}
}
