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
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
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

    /**
     * Marker written to NSUserDefaults on first use. UserDefaults lives in the app container and
     * IS destroyed when the app is deleted; the Keychain is NOT. Its absence therefore means
     * "this install has never run before".
     */
    private const val INSTALL_MARKER = "id.homebase.api.securestorage.installed"

    init {
        purgeKeychainIfFreshInstall()
    }

    /**
     * Drop Keychain state left behind by a previous install.
     *
     * iOS deliberately preserves Keychain items across app deletion, which for us is actively
     * harmful: this service holds both the YouAuth credentials and the SQLCipher key
     * ([id.homebase.api.sync.database.DatabaseKeyManager]), while the database file itself lives
     * in the app container and IS deleted. A reinstall would otherwise restore a session for an
     * identity whose local data is gone, and hand a brand-new empty database the key of the old
     * one — a mismatch that makes every subsequent DB read throw.
     *
     * Keeping this in `init` means it runs exactly once, on the first touch of this object,
     * before any get/put can observe stale values — no startup-ordering wiring required.
     */
    private fun purgeKeychainIfFreshInstall() {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.boolForKey(INSTALL_MARKER)) return

        clear()
        defaults.setBool(true, INSTALL_MARKER)
    }

    private fun createBaseQuery(key: String): CFDictionaryRef? {
        val dict = CFDictionaryCreateMutable(
            null, 4, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
        ) ?: return null

        // Add class
        CFDictionarySetValue(dict, kSecClass, kSecClassGenericPassword)

        // Add service
        val serviceCf = CFBridgingRetain(SERVICE_NAME)
        CFDictionarySetValue(dict, kSecAttrService, serviceCf)
        CFRelease(serviceCf)

        // Add account (key)
        val keyCf = CFBridgingRetain(key)
        CFDictionarySetValue(dict, kSecAttrAccount, keyCf)
        CFRelease(keyCf)

        return dict
    }

    actual fun put(key: String, value: String) {
        val nsString = NSString.create(string = value)
        val valueData = nsString.dataUsingEncoding(NSUTF8StringEncoding) ?: return

        val query = createBaseQuery(key) ?: return

        val attributesToUpdate = CFDictionaryCreateMutable(
            null, 2, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
        )

        val valueDataCf = CFBridgingRetain(valueData)
        CFDictionarySetValue(attributesToUpdate, kSecValueData, valueDataCf)

        CFDictionarySetValue(
            attributesToUpdate, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        )
        CFRelease(valueDataCf)

        val status = SecItemUpdate(query, attributesToUpdate)

        CFRelease(attributesToUpdate)
        CFRelease(query)

        if (status == errSecSuccess) return

        if (status == errSecItemNotFound) {
            val addDict = createBaseQuery(key) ?: return

            val valueDataCfForAdd = CFBridgingRetain(valueData)
            CFDictionarySetValue(addDict, kSecValueData, valueDataCfForAdd)
            CFRelease(valueDataCfForAdd)

            CFDictionarySetValue(addDict, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)

            SecItemAdd(addDict, null)
            CFRelease(addDict)
        }
    }

    actual fun get(key: String): String? {
        val query = createBaseQuery(key) ?: return null

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

    actual fun remove(key: String) {
        val query = createBaseQuery(key) ?: return
        SecItemDelete(query)
        CFRelease(query)
    }

    actual fun contains(key: String): Boolean {
        return get(key) != null
    }

    actual fun clear() {
        val dict = CFDictionaryCreateMutable(
            null, 2, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
        ) ?: return

        CFDictionarySetValue(dict, kSecClass, kSecClassGenericPassword)

        val serviceCf = CFBridgingRetain(SERVICE_NAME)
        CFDictionarySetValue(dict, kSecAttrService, serviceCf)
        CFRelease(serviceCf)

        SecItemDelete(dict)
        CFRelease(dict)
    }
}
