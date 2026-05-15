package id.homebase.core.share

import id.homebase.api.file.safeDeleteRecursively
import java.io.File

/**
 * Desktop (JVM) implementation of [ShareCacheStorage].
 * Desktop doesn't have share extensions, but we need an actual implementation.
 * Uses a local directory for any future share-like functionality.
 */
actual class ShareCacheStorage {

    private val cacheDir: File
        get() {
            val homeDir = System.getProperty("user.home")
            return File(homeDir, ".homebase/share").also { it.mkdirs() }
        }

    actual fun writeConversationCache(json: String) {
        File(cacheDir, "conversation_cache.json").writeText(json)
    }

    actual fun readConversationCache(): String? {
        val file = File(cacheDir, "conversation_cache.json")
        return if (file.exists()) file.readText() else null
    }

    actual fun clearConversationCache() {
        File(cacheDir, "conversation_cache.json").delete()
    }

    actual fun writeSharedContent(json: String) {
        File(cacheDir, "shared_content.json").writeText(json)
    }

    actual fun readSharedContent(): String? {
        val file = File(cacheDir, "shared_content.json")
        return if (file.exists()) file.readText() else null
    }

    actual fun clearSharedContent() {
        File(cacheDir, "shared_content.json").delete()
        safeDeleteRecursively(cacheDir.absolutePath, "shared_files")
    }

    actual fun getSharedFilesDirectory(): String {
        return File(cacheDir, "shared_files").also { it.mkdirs() }.absolutePath
    }

    actual fun writeGroupAvatar(conversationId: String, imageBytes: ByteArray) {
        // No-op on desktop
    }
}
