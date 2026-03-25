package id.homebase.core.share

import android.content.Context
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
        File(cacheDir, SHARED_FILES_SUBDIR).let { dir ->
            if (dir.exists()) dir.deleteRecursively()
        }
    }

    actual fun getSharedFilesDirectory(): String {
        return File(cacheDir, SHARED_FILES_SUBDIR).also { it.mkdirs() }.absolutePath
    }

    companion object {
        private const val SHARE_DIR = "share_extension"
        private const val CONVERSATION_CACHE_FILE = "conversation_cache.json"
        private const val SHARED_CONTENT_FILE = "shared_content.json"
        private const val SHARED_FILES_SUBDIR = "shared_files"
    }
}
