package id.homebase.core.share

import co.touchlab.kermit.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Writes conversation metadata to the share cache whenever conversations change.
 * Called from ConversationStream after data loads or updates.
 */
class ShareConversationCacheWriter(
    private val cacheStorage: ShareCacheStorage,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun updateCache(conversations: List<ShareableConversation>, userDomain: String) {
        try {
            val data = ShareConversationCacheData(
                conversations = conversations.sortedByDescending { it.lastMessageTimestamp },
                updatedAt = kotlin.time.Clock.System.now().toEpochMilliseconds(),
                userDomain = userDomain,
            )
            val encoded = json.encodeToString(data)
            cacheStorage.writeConversationCache(encoded)
        } catch (e: Exception) {
            Logger.e("ShareCacheWriter") { "Failed to update share cache: ${e.message}" }
        }
    }
}
