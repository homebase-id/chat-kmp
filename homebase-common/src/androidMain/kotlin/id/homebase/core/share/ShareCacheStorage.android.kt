package id.homebase.core.share

import android.content.Context
import id.homebase.api.file.safeDeleteRecursively
import java.io.File

/**
 * Android implementation of [ShareCacheStorage].
 * Uses the app's internal files directory since the share activity runs
 * in the same process and has direct access to the database and DI.
 */
actual class ShareCacheStorage(private val context: Context) {

    private val cacheDir: File
        get() = File(context.filesDir, SHARE_DIR).also { it.mkdirs() }

    actual fun writeConversationCache(json: String) {
        File(cacheDir, CONVERSATION_CACHE_FILE).writeText(json)
    }

    actual fun readConversationCache(): String? {
        val file = File(cacheDir, CONVERSATION_CACHE_FILE)
        return if (file.exists()) file.readText() else null
    }

    actual fun clearConversationCache() {
        File(cacheDir, CONVERSATION_CACHE_FILE).delete()
    }

    actual fun writeSharedContent(json: String) {
        File(cacheDir, SHARED_CONTENT_FILE).writeText(json)
    }

    actual fun readSharedContent(): String? {
        val file = File(cacheDir, SHARED_CONTENT_FILE)
        return if (file.exists()) file.readText() else null
    }

    actual fun clearSharedContent() {
        File(cacheDir, SHARED_CONTENT_FILE).delete()
        // Clean up any shared media files
        safeDeleteRecursively(cacheDir.absolutePath, SHARED_FILES_SUBDIR)
    }

    actual fun getSharedFilesDirectory(): String {
        return File(cacheDir, SHARED_FILES_SUBDIR).also { it.mkdirs() }.absolutePath
    }

    actual fun writeGroupAvatar(conversationId: String, imageBytes: ByteArray) {
        // No-op on Android — share shortcuts use HomebaseImageLoader directly
    }

    companion object {
        private const val SHARE_DIR = "share_extension"
        private const val CONVERSATION_CACHE_FILE = "conversation_cache.json"
        private const val SHARED_CONTENT_FILE = "shared_content.json"
        private const val SHARED_FILES_SUBDIR = "shared_files"
    }
}
