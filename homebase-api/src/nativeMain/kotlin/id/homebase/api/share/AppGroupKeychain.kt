@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package id.homebase.api.share

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
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessGroup
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

/** Each process reads this from its own Info.plist, so the app and every extension must carry it. */
internal val APP_GROUP_ID: String = NSBundle.mainBundle.infoDictionary
    ?.get("AppGroupIdentifier") as? String ?: "group.id.homebase.feed"

/**
 * Generic-password items in the App Group's keychain access group under one [service], readable
 * by the app and its extensions. Never point this at `SecureStorage`'s service: that one holds
 * the database key.
 */
internal class AppGroupKeychain(private val service: String) {

    fun put(key: String, value: String) {
        val valueData = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return

        val query = createQuery(key) ?: return
        val attrs = CFDictionaryCreateMutable(
            null, 2, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
        )
        val valueCf = CFBridgingRetain(valueData)
        CFDictionarySetValue(attrs, kSecValueData, valueCf)
        CFDictionarySetValue(attrs, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        CFRelease(valueCf)

        val status = SecItemUpdate(query, attrs)
        CFRelease(attrs)
        CFRelease(query)

        if (status == errSecItemNotFound) {
            val addDict = createQuery(key) ?: return
            val addValueCf = CFBridgingRetain(valueData)
            CFDictionarySetValue(addDict, kSecValueData, addValueCf)
            CFRelease(addValueCf)
            CFDictionarySetValue(addDict, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            SecItemAdd(addDict, null)
            CFRelease(addDict)
        }
    }

    fun get(key: String): String? {
        val query = createQuery(key) ?: return null
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)

        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            CFRelease(query)
            if (status != errSecSuccess || result.value == null) return null
            val data = CFBridgingRelease(result.value) as? NSData ?: return null
            return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
        }
    }

    fun delete(key: String) {
        val query = createQuery(key) ?: return
        SecItemDelete(query)
        CFRelease(query)
    }

    private fun createQuery(key: String): CFDictionaryRef? {
        val dict = CFDictionaryCreateMutable(
            null, 6, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
        ) ?: return null

        CFDictionarySetValue(dict, kSecClass, kSecClassGenericPassword)

        val serviceCf = CFBridgingRetain(service)
        CFDictionarySetValue(dict, kSecAttrService, serviceCf)
        CFRelease(serviceCf)

        val groupCf = CFBridgingRetain(APP_GROUP_ID)
        CFDictionarySetValue(dict, kSecAttrAccessGroup, groupCf)
        CFRelease(groupCf)

        val keyCf = CFBridgingRetain(key)
        CFDictionarySetValue(dict, kSecAttrAccount, keyCf)
        CFRelease(keyCf)

        return dict
    }
}
