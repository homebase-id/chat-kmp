@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package id.homebase.core.share

import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS implementation of [ShareCacheStorage].
 * Reads/writes to the App Group container so the share extension can access conversation data.
 * The conversation cache is encrypted using a key stored in the shared keychain.
 */
actual class ShareCacheStorage {

    private val fileManager = NSFileManager.defaultManager

    private val containerUrl: String?
        get() = fileManager.containerURLForSecurityApplicationGroupIdentifier(APP_GROUP_ID)
            ?.path

    actual fun writeConversationCache(json: String) {
        val dir = containerUrl ?: run {
            Logger.e(TAG) { "App Group container not available" }
            return
        }
        ensureDirectory(dir)

        // Encrypt the cache
        val key = getOrCreateEncryptionKey()
        if (key != null) {
            val encrypted = xorEncrypt(json.encodeToByteArray(), key)
            val nsData = encrypted.toNSData()
            nsData?.writeToFile("$dir/$CONVERSATION_CACHE_FILE", atomically = true)
        } else {
            // Fallback: write unencrypted if key management fails
            Logger.w(TAG) { "Encryption key unavailable, writing unencrypted cache" }
            val nsString = NSString.create(string = json)
            nsString.dataUsingEncoding(NSUTF8StringEncoding)
                ?.writeToFile("$dir/$CONVERSATION_CACHE_FILE", atomically = true)
        }
    }

    actual fun readConversationCache(): String? {
        val dir = containerUrl ?: return null
        val path = "$dir/$CONVERSATION_CACHE_FILE"

        if (!fileManager.fileExistsAtPath(path)) return null

        val key = getOrCreateEncryptionKey()
        val data = NSData.create(contentsOfFile = path) ?: return null

        return if (key != null) {
            val encrypted = data.toByteArray()
            val decrypted = xorEncrypt(encrypted, key) // XOR is symmetric
            decrypted.decodeToString()
        } else {
            NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
        }
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

    private fun ensureDirectory(path: String) {
        if (!fileManager.fileExistsAtPath(path)) {
            fileManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }

    // Simple XOR-based encryption for the conversation cache.
    // This is lightweight but sufficient since the App Group container is already sandboxed
    // and we're only protecting conversation metadata (names, IDs), not sensitive content.
    private fun xorEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        return ByteArray(data.size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }
    }

    private fun getOrCreateEncryptionKey(): ByteArray? {
        val existing = keychainGet(ENCRYPTION_KEY_NAME)
        if (existing != null) return existing.encodeToByteArray()

        // Generate a new key
        val newKey = buildString {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            repeat(32) { append(chars.random()) }
        }
        keychainPut(ENCRYPTION_KEY_NAME, newKey)
        return newKey.encodeToByteArray()
    }

    // Shared keychain operations using the App Group service name
    private fun keychainPut(key: String, value: String) {
        val nsString = NSString.create(string = value)
        val valueData = nsString.dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val query = createKeychainQuery(key) ?: return

        val attrs = CFDictionaryCreateMutable(
            null, 2, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
        )
        val valueDataCf = CFBridgingRetain(valueData)
        CFDictionarySetValue(attrs, kSecValueData, valueDataCf)
        CFDictionarySetValue(attrs, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
        CFRelease(valueDataCf)

        val status = SecItemUpdate(query, attrs)
        CFRelease(attrs)
        CFRelease(query)

        if (status == errSecItemNotFound) {
            val addDict = createKeychainQuery(key) ?: return
            val addValueCf = CFBridgingRetain(valueData)
            CFDictionarySetValue(addDict, kSecValueData, addValueCf)
            CFRelease(addValueCf)
            CFDictionarySetValue(addDict, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
            SecItemAdd(addDict, null)
            CFRelease(addDict)
        }
    }

    private fun keychainGet(key: String): String? {
        val query = createKeychainQuery(key) ?: return null
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)

        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            CFRelease(query)

            if (status == errSecSuccess && result.value != null) {
                val data = CFBridgingRelease(result.value) as? NSData
                if (data != null) {
                    return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
                }
            }
        }
        return null
    }

    private fun createKeychainQuery(key: String): CFDictionaryRef? {
        val dict = CFDictionaryCreateMutable(
            null, 4, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
        ) ?: return null

        CFDictionarySetValue(dict, kSecClass, kSecClassGenericPassword)

        val serviceCf = CFBridgingRetain(KEYCHAIN_SERVICE)
        CFDictionarySetValue(dict, kSecAttrService, serviceCf)
        CFRelease(serviceCf)

        val keyCf = CFBridgingRetain(key)
        CFDictionarySetValue(dict, kSecAttrAccount, keyCf)
        CFRelease(keyCf)

        return dict
    }

    companion object {
        private const val TAG = "ShareCacheStorage"
        private const val APP_GROUP_ID = "group.id.homebase.chat"
        private const val KEYCHAIN_SERVICE = "id.homebase.share"
        private const val ENCRYPTION_KEY_NAME = "share_cache_encryption_key"
        private const val CONVERSATION_CACHE_FILE = "share_conversation_cache.enc"
        private const val SHARED_CONTENT_FILE = "shared_content.json"
        private const val SHARED_FILES_SUBDIR = "shared_files"
    }
}

// Helper extensions for NSData <-> ByteArray conversion
@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData? {
    if (isEmpty()) return null
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val result = ByteArray(size)
    result.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), bytes, length)
    }
    return result
}
