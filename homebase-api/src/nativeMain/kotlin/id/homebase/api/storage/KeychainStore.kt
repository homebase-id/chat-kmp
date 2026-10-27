@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.api.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
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
import platform.Security.SecItemUpdate
import platform.Security.errSecAllocate
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
import platform.darwin.OSStatus

/** UTF-8 generic-password items under one [service], optionally in a shared [accessGroup]. */
internal class KeychainStore(private val service: String, private val accessGroup: String? = null) {

    class Read(val status: OSStatus, val value: String?)

    fun put(key: String, value: String) {
        val valueData = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding) ?: return

        val query = createQuery(key) ?: return
        val attrs = CFDictionaryCreateMutable(
            null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
        )
        setValueData(attrs, valueData)
        val status = SecItemUpdate(query, attrs)
        CFRelease(attrs)
        CFRelease(query)

        if (status == errSecItemNotFound) {
            val addDict = createQuery(key) ?: return
            setValueData(addDict, valueData)
            SecItemAdd(addDict, null)
            CFRelease(addDict)
        }
    }

    fun get(key: String): String? = read(key).value

    /** A null [key] matches any item of the service, which still reports a locked Keychain. */
    fun read(key: String?): Read {
        val query = createQuery(key) ?: return Read(errSecAllocate, null)
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)

        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            CFRelease(query)
            if (status == errSecSuccess && result.value != null) {
                val data = CFBridgingRelease(result.value) as? NSData
                if (data != null) {
                    return Read(status, NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString())
                }
            }
            return Read(status, null)
        }
    }

    fun delete(key: String) = deleteMatching(key)

    fun clear() = deleteMatching(null)

    private fun deleteMatching(key: String?) {
        val query = createQuery(key) ?: return
        SecItemDelete(query)
        CFRelease(query)
    }

    private fun setValueData(dict: CFDictionaryRef?, valueData: NSData) {
        val valueCf = CFBridgingRetain(valueData)
        CFDictionarySetValue(dict, kSecValueData, valueCf)
        CFRelease(valueCf)
        CFDictionarySetValue(dict, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
    }

    private fun createQuery(key: String?): CFDictionaryRef? {
        val dict = CFDictionaryCreateMutable(
            null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr
        ) ?: return null

        CFDictionarySetValue(dict, kSecClass, kSecClassGenericPassword)
        setString(dict, kSecAttrService, service)
        accessGroup?.let { setString(dict, kSecAttrAccessGroup, it) }
        key?.let { setString(dict, kSecAttrAccount, it) }
        return dict
    }

    private fun setString(dict: CFDictionaryRef, attr: CFStringRef?, value: String) {
        val cf = CFBridgingRetain(value)
        CFDictionarySetValue(dict, attr, cf)
        CFRelease(cf)
    }
}
