package id.homebase.notifshared

import id.homebase.api.notifications.fetchChatNotificationPreview
import id.homebase.api.share.NotificationExtensionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** The Notification Service Extension's entry into the shared decrypt path. */
object NotificationPreviewBridge {

    // Well inside the extension's ~30 s window, leaving room for the avatar fetch after it.
    private val RESOLVE_TIMEOUT = 10.seconds

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The user's notification content level, or null when the app hasn't published one. */
    fun contentLevel(): String? = NotificationExtensionStore.readContentLevel()

    /**
     * Calls [completion] exactly once, on a background thread, with the decrypted text of
     * [messageId] — or a null text and the reason it was not shown: no shared credentials, not
     * plain text, or not fetched within [RESOLVE_TIMEOUT]. The reason never carries content.
     */
    fun resolvePreview(
        conversationId: String,
        messageId: String,
        completion: (text: String?, missReason: String?) -> Unit,
    ) {
        val credentials = NotificationExtensionStore.readCredentials()
            ?: return completion(null, "no shared credentials")
        scope.launch {
            val preview = withTimeoutOrNull(RESOLVE_TIMEOUT) {
                fetchChatNotificationPreview(credentials, conversationId, messageId)
            }
            if (preview == null) completion(null, "timed out after $RESOLVE_TIMEOUT")
            else completion(preview.text, preview.missReason)
        }
    }
}
