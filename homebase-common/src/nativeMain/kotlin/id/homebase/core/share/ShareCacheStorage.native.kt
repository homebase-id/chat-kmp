@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package id.homebase.core.share

import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile

/**
 * iOS implementation of [ShareCacheStorage].
 * Reads/writes to the App Group container so the share extension can access conversation data.
 */
actual class ShareCacheStorage {

    private val fileManager = NSFileManager.defaultManager

    private val containerUrl: String?
        get() = fileManager.containerURLForSecurityApplicationGroupIdentifier(APP_GROUP_ID)
            ?.path

    actual fun writeConversationCache(json: String) {
        val dir = containerUrl ?: run {
            Logger.e(tag = TAG) { "App Group container not available" }
            return
        }
        ensureDirectory(dir)
        val nsString = NSString.create(string = json)
        nsString.dataUsingEncoding(NSUTF8StringEncoding)
            ?.writeToFile("$dir/$CONVERSATION_CACHE_FILE", atomically = true)
    }

    actual fun readConversationCache(): String? {
        val dir = containerUrl ?: return null
        val path = "$dir/$CONVERSATION_CACHE_FILE"
        if (!fileManager.fileExistsAtPath(path)) return null
        val data = NSData.create(contentsOfFile = path) ?: return null
        return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    actual fun clearConversationCache() {
        val dir = containerUrl ?: return
        val path = "$dir/$CONVERSATION_CACHE_FILE"
        try {
            fileManager.removeItemAtPath(path, null)
        } catch (_: Exception) { }
    }

    actual fun writeSharedContent(json: String) {
        val dir = containerUrl ?: return
        ensureDirectory(dir)
        val nsString = NSString.create(string = json)
        nsString.dataUsingEncoding(NSUTF8StringEncoding)
            ?.writeToFile("$dir/$SHARED_CONTENT_FILE", atomically = true)
    }

    actual fun readSharedContent(): String? {
        val dir = containerUrl ?: return null
        val path = "$dir/$SHARED_CONTENT_FILE"
        if (!fileManager.fileExistsAtPath(path)) return null
        val data = NSData.create(contentsOfFile = path) ?: return null
        return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    actual fun clearSharedContent() {
        val dir = containerUrl ?: return
        try {
            fileManager.removeItemAtPath("$dir/$SHARED_CONTENT_FILE", null)
            fileManager.removeItemAtPath("$dir/$SHARED_FILES_SUBDIR", null)
        } catch (_: Exception) {
            // Ignore cleanup errors
        }
    }

    actual fun getSharedFilesDirectory(): String {
        val dir = containerUrl ?: return ""
        val filesDir = "$dir/$SHARED_FILES_SUBDIR"
        ensureDirectory(filesDir)
        return filesDir
    }

    actual fun writeGroupAvatar(conversationId: String, imageBytes: ByteArray) {
        val dir = containerUrl ?: return
        val avatarsDir = "$dir/$AVATARS_SUBDIR"
        ensureDirectory(avatarsDir)
        imageBytes.toNSData()?.writeToFile("$avatarsDir/$conversationId.png", atomically = true)
    }

    private fun ensureDirectory(path: String) {
        if (!fileManager.fileExistsAtPath(path)) {
            fileManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    companion object {
        private const val TAG = "ShareCacheStorage"
        private val APP_GROUP_ID: String = NSBundle.mainBundle.infoDictionary
            ?.get("AppGroupIdentifier") as? String ?: "group.id.homebase.feed"
        private const val CONVERSATION_CACHE_FILE = "share_conversation_cache.json"
        private const val SHARED_CONTENT_FILE = "shared_content.json"
        private const val SHARED_FILES_SUBDIR = "shared_files"
        private const val AVATARS_SUBDIR = "avatars"
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData? {
    if (isEmpty()) return null
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
