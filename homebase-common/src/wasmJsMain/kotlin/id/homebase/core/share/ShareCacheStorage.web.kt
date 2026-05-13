package id.homebase.core.share

/**
 * Web implementation of [ShareCacheStorage].
 * Web doesn't support share extensions. This is a no-op implementation.
 */
actual class ShareCacheStorage {

    actual fun writeConversationCache(json: String) {
        // No-op on web
    }

    actual fun readConversationCache(): String? = null

    actual fun clearConversationCache() {
        // No-op on web
    }

    actual fun writeSharedContent(json: String) {
        // No-op on web
    }

    actual fun readSharedContent(): String? = null

    actual fun clearSharedContent() {
        // No-op on web
    }

    actual fun getSharedFilesDirectory(): String = ""

    actual fun writeGroupAvatar(conversationId: String, imageBytes: ByteArray) {
        // No-op on web
    }
}
