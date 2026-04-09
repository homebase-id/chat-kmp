@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package id.homebase.api.share

import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ptr
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecAttrAccessGroup
import platform.Security.kSecValueData

/**
 * iOS implementation that writes auth status to the shared keychain
 * so the share extension can check if the user is logged in.
 */
actual object ShareAuthBridge {

    private const val SERVICE = "id.homebase.share.auth"
    private val ACCESS_GROUP: String = NSBundle.mainBundle.infoDictionary
        ?.get("AppGroupIdentifier") as? String ?: "group.id.homebase.feed"
    private const val KEY_AUTH_ACTIVE = "share_auth_active"
    private const val KEY_USER_DOMAIN = "share_user_domain"

    actual fun setAuthenticated(isAuthenticated: Boolean, userDomain: String) {
        try {
            putKeychain(KEY_AUTH_ACTIVE, if (isAuthenticated) "true" else "false")
            putKeychain(KEY_USER_DOMAIN, userDomain)
        } catch (e: Exception) {
            Logger.e(tag = "ShareAuthBridge") { "Failed to set auth status: ${e.message}" }
        }
    }

    actual fun clearAuth() {
        try {
            deleteKeychain(KEY_AUTH_ACTIVE)
            deleteKeychain(KEY_USER_DOMAIN)
        } catch (e: Exception) {
            Logger.e(tag = "ShareAuthBridge") { "Failed to clear auth status: ${e.message}" }
        }
    }

    private fun putKeychain(key: String, value: String) {
        val nsString = NSString.create(string = value)
        val valueData = nsString.dataUsingEncoding(NSUTF8StringEncoding) ?: return

        val query = createQuery(key) ?: return

        val attrs = CFDictionaryCreateMutable(
            null, 2, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
        )
        val valueCf = CFBridgingRetain(valueData)
        CFDictionarySetValue(attrs, kSecValueData, valueCf)
        CFDictionarySetValue(attrs, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
        CFRelease(valueCf)

        val status = SecItemUpdate(query, attrs)
        CFRelease(attrs)
        CFRelease(query)

        if (status == errSecItemNotFound) {
            val addDict = createQuery(key) ?: return
            val addValueCf = CFBridgingRetain(valueData)
            CFDictionarySetValue(addDict, kSecValueData, addValueCf)
            CFRelease(addValueCf)
            CFDictionarySetValue(addDict, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
            SecItemAdd(addDict, null)
            CFRelease(addDict)
        }
    }

    private fun deleteKeychain(key: String) {
        val query = createQuery(key) ?: return
        SecItemDelete(query)
        CFRelease(query)
    }

    private fun createQuery(key: String): CFDictionaryRef? {
        val dict = CFDictionaryCreateMutable(
            null, 4, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
        ) ?: return null

        CFDictionarySetValue(dict, kSecClass, kSecClassGenericPassword)

        val serviceCf = CFBridgingRetain(SERVICE)
        CFDictionarySetValue(dict, kSecAttrService, serviceCf)
        CFRelease(serviceCf)

        val groupCf = CFBridgingRetain(ACCESS_GROUP)
        CFDictionarySetValue(dict, kSecAttrAccessGroup, groupCf)
        CFRelease(groupCf)

        val keyCf = CFBridgingRetain(key)
        CFDictionarySetValue(dict, kSecAttrAccount, keyCf)
        CFRelease(keyCf)

        return dict
    }
}
