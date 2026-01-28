@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package id.homebase.api.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS implementation of SecureStorage using Keychain Services.
 *
 * Keys are stored securely in the iOS Keychain which provides:
 * - Hardware-backed encryption on devices with Secure Enclave
 * - Data protection across app restarts
 * - Automatic data encryption when device is locked
 */
actual object SecureStorage {
    private const val SERVICE_NAME = "id.homebase.api.securestorage"

    private fun createBaseQuery(key: String): CFDictionaryRef {
        val dict =
            CFDictionaryCreateMutable(
                null,
                4,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr
            )
        CFDictionarySetValue(dict, kSecClass as CFTypeRef?, kSecClassGenericPassword as CFTypeRef?)
        CFDictionarySetValue(dict, kSecAttrService as CFTypeRef?, CFBridgingRetain(SERVICE_NAME))
        CFDictionarySetValue(dict, kSecAttrAccount as CFTypeRef?, CFBridgingRetain(key))
        return dict as CFDictionaryRef
    }

    actual fun put(key: String, value: String) {
        val nsString = NSString.create(string = value)
        val valueData = nsString.dataUsingEncoding(NSUTF8StringEncoding) ?: return

        // Delete existing item first
        remove(key)

        // Create query with value
        val dict =
            CFDictionaryCreateMutable(
                null,
                5,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr
            )
        CFDictionarySetValue(dict, kSecClass as CFTypeRef?, kSecClassGenericPassword as CFTypeRef?)
        CFDictionarySetValue(dict, kSecAttrService as CFTypeRef?, CFBridgingRetain(SERVICE_NAME))
        CFDictionarySetValue(dict, kSecAttrAccount as CFTypeRef?, CFBridgingRetain(key))
        CFDictionarySetValue(dict, kSecValueData as CFTypeRef?, CFBridgingRetain(valueData))

        SecItemAdd(dict as CFDictionaryRef, null)
    }

    actual fun get(key: String): String? {
        val dict =
            CFDictionaryCreateMutable(
                null,
                5,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr
            )
        CFDictionarySetValue(dict, kSecClass as CFTypeRef?, kSecClassGenericPassword as CFTypeRef?)
        CFDictionarySetValue(dict, kSecAttrService as CFTypeRef?, CFBridgingRetain(SERVICE_NAME))
        CFDictionarySetValue(dict, kSecAttrAccount as CFTypeRef?, CFBridgingRetain(key))
        CFDictionarySetValue(dict, kSecReturnData as CFTypeRef?, CFBridgingRetain(true))
        CFDictionarySetValue(dict, kSecMatchLimit as CFTypeRef?, kSecMatchLimitOne as CFTypeRef?)

        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(dict as CFDictionaryRef, result.ptr)

            if (status == errSecSuccess && result.value != null) {
                val data = CFBridgingRelease(result.value) as? NSData ?: return null
                return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
            }
        }
        return null
    }

    actual fun remove(key: String) {
        val query = createBaseQuery(key)
        SecItemDelete(query)
    }

    actual fun contains(key: String): Boolean {
        return get(key) != null
    }

    actual fun clear() {
        val dict =
            CFDictionaryCreateMutable(
                null,
                2,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr
            )
        CFDictionarySetValue(dict, kSecClass as CFTypeRef?, kSecClassGenericPassword as CFTypeRef?)
        CFDictionarySetValue(dict, kSecAttrService as CFTypeRef?, CFBridgingRetain(SERVICE_NAME))

        SecItemDelete(dict as CFDictionaryRef)
    }
}
