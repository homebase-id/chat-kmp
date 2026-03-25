package id.homebase.core.share

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.Json

/**
 * Reads a [SharedContentDescriptor] from cache storage and provides it for sending.
 * Used by the main app when receiving a handoff from the iOS share extension,
 * and by the Android share activity to serialize/deserialize shared content.
 */
class ShareContentProcessor(
    private val cacheStorage: ShareCacheStorage,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Read the pending shared content descriptor.
     * Returns null if no shared content is waiting.
     */
    fun readPendingContent(): SharedContentDescriptor? {
        return try {
            val raw = cacheStorage.readSharedContent() ?: return null
            json.decodeFromString<SharedContentDescriptor>(raw)
        } catch (e: Exception) {
            Logger.e("ShareContentProcessor") { "Failed to read shared content: ${e.message}" }
            null
        }
    }

    /**
     * Resolve a shared file name to its full path in the shared files directory.
     */
    fun resolveFilePath(fileName: String): String {
        return "${cacheStorage.getSharedFilesDirectory()}/$fileName"
    }

    /**
     * Clean up after shared content has been processed.
     */
    fun cleanup() {
        cacheStorage.clearSharedContent()
    }

    /**
     * Write a shared content descriptor (used by Android share activity or iOS extension).
     */
    fun writeContent(descriptor: SharedContentDescriptor) {
        try {
            val encoded = json.encodeToString(SharedContentDescriptor.serializer(), descriptor)
            cacheStorage.writeSharedContent(encoded)
        } catch (e: Exception) {
            Logger.e("ShareContentProcessor") { "Failed to write shared content: ${e.message}" }
        }
    }
}
