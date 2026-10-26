package id.homebase.notifshared

import id.homebase.api.share.NotificationExtensionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The Notification Service Extension's entry into the shared decrypt path. */
object NotificationPreviewBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The user's notification content level, or null when the app hasn't published one. */
    fun contentLevel(): String? = NotificationExtensionStore.readContentLevel()

    /**
     * Calls [completion] exactly once, on a background thread, with the decrypted text of
     * [messageId] — or a null text and the reason it was not shown: no shared credentials, not
     * plain text, or not read within the preview's deadline. The reason never carries content.
     */
    fun resolvePreview(
        conversationId: String,
        messageId: String,
        completion: (text: String?, missReason: String?) -> Unit,
    ) {
        val credentials = NotificationExtensionStore.readCredentials()
            ?: return completion(null, "no shared credentials")
        scope.launch {
            val preview = fetchChatNotificationPreview(credentials, conversationId, messageId)
            completion(preview.text, preview.missReason)
        }
    }
}
