package id.homebase.api.sync.database

import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.storage.SecureStorage

internal interface RawKeyStore {
    fun get(): String?
    fun put(hex: String)
    fun remove()
}

internal object SecureStorageRawKeyStore : RawKeyStore {
    private const val KEY_DB_RAW_KEY = "odin_db_raw_key"

    override fun get(): String? = SecureStorage.get(KEY_DB_RAW_KEY)
    override fun put(hex: String) = SecureStorage.put(KEY_DB_RAW_KEY, hex)
    override fun remove() = SecureStorage.remove(KEY_DB_RAW_KEY)
}

/** The 32-byte key SQLCipher takes as `x'<64 hex>'`, skipping its per-connection PBKDF2. */
internal object SqlCipherRawKey {
    const val BYTES = 32
    private const val HEX_LENGTH = BYTES * 2

    fun format(hex: String): String = "x'$hex'"

    fun isHex(value: String): Boolean =
        value.length == HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

    fun newHex(): String = ByteArrayUtil.getRndByteArray(BYTES).toHexString()
}
