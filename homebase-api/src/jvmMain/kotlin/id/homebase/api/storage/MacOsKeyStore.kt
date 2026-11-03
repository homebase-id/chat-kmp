package id.homebase.api.storage

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

internal class MacKeychainException(val status: Int) : Exception("Keychain status $status")

private const val ERR_SEC_SUCCESS = 0
private const val ERR_SEC_ITEM_NOT_FOUND = -25300
private const val ERR_SEC_DUPLICATE_ITEM = -25299

internal fun macStatusToRead(status: Int, value: String?): OsKeyRead =
    when {
        status == ERR_SEC_SUCCESS && value != null -> OsKeyRead.Found(value)
        status == ERR_SEC_ITEM_NOT_FOUND -> OsKeyRead.Absent
        else -> OsKeyRead.Unreachable(MacKeychainException(status))
    }

internal fun macAddNeedsUpdate(status: Int): Boolean = status == ERR_SEC_DUPLICATE_ITEM

private interface CoreFoundation : Library {
    fun CFDictionaryCreateMutable(alloc: Pointer?, capacity: Long, keyCallbacks: Pointer, valueCallbacks: Pointer): Pointer
    fun CFDictionarySetValue(dict: Pointer, key: Pointer, value: Pointer)
    fun CFStringCreateWithCString(alloc: Pointer?, string: String, encoding: Int): Pointer
    fun CFDataCreate(alloc: Pointer?, bytes: ByteArray, length: Long): Pointer
    fun CFDataGetLength(data: Pointer): Long
    fun CFDataGetBytePtr(data: Pointer): Pointer
    fun CFRelease(ref: Pointer)
}

private interface Security : Library {
    fun SecItemCopyMatching(query: Pointer, result: PointerByReference): Int
    fun SecItemAdd(attributes: Pointer, result: Pointer?): Int
    fun SecItemUpdate(query: Pointer, attributesToUpdate: Pointer): Int
    fun SecItemDelete(query: Pointer): Int
}

private const val CF_PATH = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
private const val SECURITY_PATH = "/System/Library/Frameworks/Security.framework/Security"
private const val CF_STRING_ENCODING_UTF8 = 0x08000100

/** Generic password in the file-based login keychain; the data-protection keychain needs entitlements Conveyor doesn't grant. */
internal class MacOsKeyStore(private val service: String, private val account: String) : OsKeyStore {
    private val cf by lazy { Native.load(CF_PATH, CoreFoundation::class.java) }
    private val security by lazy { Native.load(SECURITY_PATH, Security::class.java) }
    private val secLib by lazy { NativeLibrary.getInstance(SECURITY_PATH) }
    private val cfLib by lazy { NativeLibrary.getInstance(CF_PATH) }

    private fun secConst(name: String): Pointer = secLib.getGlobalVariableAddress(name).getPointer(0)

    private class Refs(private val cf: CoreFoundation) {
        private val owned = mutableListOf<Pointer>()
        fun <T : Pointer> own(p: T): T = p.also { owned += it }
        fun close() = owned.forEach { cf.CFRelease(it) }
    }

    private fun newDict(refs: Refs): Pointer =
        refs.own(
            cf.CFDictionaryCreateMutable(
                null,
                0,
                cfLib.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"),
                cfLib.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks"),
            ),
        )

    private fun identity(refs: Refs): Pointer {
        val dict = newDict(refs)
        cf.CFDictionarySetValue(dict, secConst("kSecClass"), secConst("kSecClassGenericPassword"))
        cf.CFDictionarySetValue(dict, secConst("kSecAttrService"), refs.own(cf.CFStringCreateWithCString(null, service, CF_STRING_ENCODING_UTF8)))
        cf.CFDictionarySetValue(dict, secConst("kSecAttrAccount"), refs.own(cf.CFStringCreateWithCString(null, account, CF_STRING_ENCODING_UTF8)))
        return dict
    }

    private fun data(refs: Refs, value: String): Pointer {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return refs.own(cf.CFDataCreate(null, bytes, bytes.size.toLong()))
    }

    override fun read(): OsKeyRead = guardedOsRead {
        val refs = Refs(cf)
        try {
            val query = identity(refs)
            cf.CFDictionarySetValue(query, secConst("kSecReturnData"), cfLib.getGlobalVariableAddress("kCFBooleanTrue").getPointer(0))
            cf.CFDictionarySetValue(query, secConst("kSecMatchLimit"), secConst("kSecMatchLimitOne"))
            val result = PointerByReference()
            val status = security.SecItemCopyMatching(query, result)
            val value = if (status == ERR_SEC_SUCCESS) {
                val dataRef = refs.own(result.value)
                val length = cf.CFDataGetLength(dataRef).toInt()
                String(cf.CFDataGetBytePtr(dataRef).getByteArray(0, length), Charsets.UTF_8)
            } else {
                null
            }
            macStatusToRead(status, value)
        } finally {
            refs.close()
        }
    }

    override fun write(value: String) {
        val refs = Refs(cf)
        try {
            val add = identity(refs)
            cf.CFDictionarySetValue(add, secConst("kSecValueData"), data(refs, value))
            val status = security.SecItemAdd(add, null)
            if (macAddNeedsUpdate(status)) {
                val change = newDict(refs)
                cf.CFDictionarySetValue(change, secConst("kSecValueData"), data(refs, value))
                val updated = security.SecItemUpdate(identity(refs), change)
                if (updated != ERR_SEC_SUCCESS) throw MacKeychainException(updated)
            } else if (status != ERR_SEC_SUCCESS) {
                throw MacKeychainException(status)
            }
        } finally {
            refs.close()
        }
    }

    override fun delete() {
        val refs = Refs(cf)
        try {
            val status = security.SecItemDelete(identity(refs))
            if (status != ERR_SEC_SUCCESS && status != ERR_SEC_ITEM_NOT_FOUND) throw MacKeychainException(status)
        } finally {
            refs.close()
        }
    }
}
