package id.homebase.api.youauth

import id.homebase.api.common.SecureByteArray
import id.homebase.api.storage.SecureStorage
import kotlin.io.encoding.Base64
import id.homebase.api.common.OdinId

/**
 * Factory for creating OdinClient instances with proper configuration. Handles loading credentials
 * from SecureStorage.
 */
object CredentialStorage {

    fun getCredentials(): StoredIdentity? {
        val identity =
            OdinId(SecureStorage.get(YouAuthStorageKeys.IDENTITY) ?: return null)

        val sharedSecretBase64 =
            SecureStorage.get(YouAuthStorageKeys.SHARED_SECRET) ?: return null

        val clientAuthToken =
            SecureStorage.get(YouAuthStorageKeys.CLIENT_AUTH_TOKEN) ?: return null

        val sharedSecret =
            try {
                Base64.decode(sharedSecretBase64)
            } catch (e: Exception) {
                return null
            }

        return StoredIdentity(
            identity = identity,
            sharedSecret = SecureByteArray(sharedSecret),
            clientAuthToken = clientAuthToken
        )
    }


    /** Save authentication credentials to secure storage. */
    fun saveCredentials(identity: OdinId, clientAuthToken: String, sharedSecret: ByteArray) {
        SecureStorage.put(YouAuthStorageKeys.IDENTITY, identity.domainName)
        SecureStorage.put(YouAuthStorageKeys.CLIENT_AUTH_TOKEN, clientAuthToken)
        SecureStorage.put(YouAuthStorageKeys.SHARED_SECRET, Base64.encode(sharedSecret))
    }

    /** Clear all stored credentials. */
    fun clearCredentials() {
        SecureStorage.remove(YouAuthStorageKeys.IDENTITY)
        SecureStorage.remove(YouAuthStorageKeys.CLIENT_AUTH_TOKEN)
        SecureStorage.remove(YouAuthStorageKeys.SHARED_SECRET)
    }

    /** Check if credentials are stored. */
    fun hasStoredCredentials(): Boolean {
        return SecureStorage.contains(YouAuthStorageKeys.IDENTITY) &&
                SecureStorage.contains(YouAuthStorageKeys.SHARED_SECRET) &&
                SecureStorage.contains(YouAuthStorageKeys.CLIENT_AUTH_TOKEN)
    }

    private fun buildAuthHeaders(clientAuthToken: String): Map<String, String> {
        return mapOf("Authorization" to "Bearer $clientAuthToken")
    }
}


data class StoredIdentity(
    val identity: OdinId,
    val sharedSecret: SecureByteArray,
    val clientAuthToken: String
)