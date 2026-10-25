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
     * [messageId] — or null when there are no shared credentials, the message isn't plain text,
     * or it can't be fetched within [RESOLVE_TIMEOUT].
     */
    fun resolvePreview(conversationId: String, messageId: String, completion: (String?) -> Unit) {
        val credentials = NotificationExtensionStore.readCredentials()
            ?: return completion(null)
        scope.launch {
            val preview = withTimeoutOrNull(RESOLVE_TIMEOUT) {
                fetchChatNotificationPreview(credentials, conversationId, messageId)
            }
            completion(preview)
        }
    }
}
