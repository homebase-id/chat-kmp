package id.homebase.api.sync.database

import dev.whyoleg.cryptography.random.CryptographyRandom
import id.homebase.api.storage.SecureStorage
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object DatabaseKeyManager {
    private const val KEY_DB_ENCRYPTION = "odin_db_encryption_key"

    @OptIn(ExperimentalEncodingApi::class)
    fun getOrGenerateKey(): String {
        val existingKey = SecureStorage.get(KEY_DB_ENCRYPTION)
        if (existingKey != null) {
            return existingKey
        }

        val newKeyBytes = CryptographyRandom.nextBytes(128)
        val newKey = Base64.encode(newKeyBytes)
        SecureStorage.put(KEY_DB_ENCRYPTION, newKey)
        return newKey
    }
}
