package id.homebase.api.sync.database

import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.storage.SecureRead
import id.homebase.api.storage.SecureStorage

internal interface RawKeyStore {
    fun read(): SecureRead
    fun put(hex: String)
}

internal object SecureStorageRawKeyStore : RawKeyStore {
    internal const val KEY_DB_RAW_KEY = "odin_db_raw_key"

    override fun read(): SecureRead = SecureStorage.read(KEY_DB_RAW_KEY)
    override fun put(hex: String) = SecureStorage.put(KEY_DB_RAW_KEY, hex)
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

internal enum class DatabaseFileState { Absent, Plaintext, Encrypted, Unreadable }

internal sealed interface RawKeyDecision {
    data class Use(val hex: String) : RawKeyDecision
    data object Create : RawKeyDecision
    data object OpenUnencrypted : RawKeyDecision
    /** Retry later; recovery must not wipe, and nothing is written to the key store. */
    data object FailUnavailable : RawKeyDecision
    /** The file's random key is gone for good, so recovery may wipe it. */
    data object FailKeyLost : RawKeyDecision
}

/**
 * A random raw key can't be re-derived, so a new one is only made when no key is stored and no
 * encrypted file depends on one; a key that can't be read now never leads to a write or a wipe.
 */
internal fun decideRawKey(key: SecureRead, file: DatabaseFileState): RawKeyDecision {
    if (file == DatabaseFileState.Unreadable) return RawKeyDecision.FailUnavailable
    return when (key) {
        is SecureRead.Found ->
            if (SqlCipherRawKey.isHex(key.value)) RawKeyDecision.Use(key.value) else decideRawKey(SecureRead.Missing, file)
        SecureRead.Missing ->
            if (file == DatabaseFileState.Encrypted) RawKeyDecision.FailKeyLost else RawKeyDecision.Create
        is SecureRead.Unavailable ->
            if (file == DatabaseFileState.Plaintext) RawKeyDecision.OpenUnencrypted else RawKeyDecision.FailUnavailable
    }
}

/**
 * The database file or its key can't be read yet, e.g. an iOS launch before the first unlock.
 * [DatabaseManager.initializeWithRecovery] rethrows it without wiping; a later launch retries.
 */
class DatabaseUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)
