package id.homebase.api.sync.database

import dev.whyoleg.cryptography.random.CryptographyRandom
import id.homebase.api.storage.SecureRead
import id.homebase.api.storage.SecureStorage
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object DatabaseKeyManager {
    private const val KEY_DB_ENCRYPTION = "odin_db_encryption_key"

    fun clearKey() {
        SecureStorage.remove(KEY_DB_ENCRYPTION)
    }

    fun getOrGenerateKey(): String =
        getOrGenerateKey(SecureStorage.read(KEY_DB_ENCRYPTION)) { SecureStorage.put(KEY_DB_ENCRYPTION, it) }

    @OptIn(ExperimentalEncodingApi::class)
    internal fun getOrGenerateKey(stored: SecureRead, put: (String) -> Unit): String = when (stored) {
        is SecureRead.Found -> stored.value
        is SecureRead.Unavailable -> throw DatabaseUnavailableException("Database passphrase unreadable", stored.cause)
        SecureRead.Missing -> Base64.encode(CryptographyRandom.nextBytes(128)).also(put)
    }
}
