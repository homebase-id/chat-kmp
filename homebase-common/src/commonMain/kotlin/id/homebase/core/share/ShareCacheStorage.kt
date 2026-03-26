package id.homebase.core.share

/**
 * Platform-specific storage for the share extension cache.
 *
 * On iOS: reads/writes to the App Group container with encryption.
 * On Android: reads/writes to the app's internal files directory (same process).
 */
expect class ShareCacheStorage {
    /** Write the conversation cache JSON for the share extension picker. */
    fun writeConversationCache(json: String)

    /** Read the conversation cache JSON. Returns null if no cache exists. */
    fun readConversationCache(): String?

    /** Write shared content descriptor JSON (iOS: to App Group for main app pickup). */
    fun writeSharedContent(json: String)

    /** Read shared content descriptor JSON. Returns null if none pending. */
    fun readSharedContent(): String?

    /** Delete the conversation cache (e.g. on logout). */
    fun clearConversationCache()

    /** Delete shared content after it has been processed. */
    fun clearSharedContent()

    /** Get the base path for shared files (iOS: App Group container, Android: cache dir). */
    fun getSharedFilesDirectory(): String

    /** Write a group avatar image to the shared avatars directory for the iOS share extension. */
    fun writeGroupAvatar(conversationId: String, imageBytes: ByteArray)
}
